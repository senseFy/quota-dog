package saien.quotadog.hostsync

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

// Standalone ABI fixture: genuine Kotlin object / instance external methods.
object NativeHostTls {
    external fun open(server: Boolean, spki: ByteArray, signer: Any, peerPin: ByteArray?): Long
    external fun receive(id: Long, ciphertext: ByteArray)
    external fun send(id: Long, plaintext: ByteArray)
    external fun outbound(id: Long): ByteArray
    external fun plaintext(id: Long): ByteArray
    external fun peerPin(id: Long): ByteArray?
    external fun close(id: Long)
}

private val tls = NativeHostTls
private const val MAX = 1024 * 1024

class DisposableSigner {
    private val pair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
    }
    val spki: ByteArray = pair.public.encoded
    val pin: ByteArray = MessageDigest.getInstance("SHA-256").digest(spki)
    var signCount = 0
        private set

    fun sign(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        signCount++
        initSign(pair.private); update(message); sign()
    }
}

private fun rejected(block: () -> Unit) {
    try { block() } catch (_: RuntimeException) { return }
    throw AssertionError("expected JNI rejection")
}

private fun unusable(id: Long) {
    rejected { tls.peerPin(id) }
    rejected { tls.plaintext(id) }
    rejected { tls.outbound(id) }
    rejected { tls.send(id, byteArrayOf(1)) }
    rejected { tls.receive(id, byteArrayOf(1)) }
}

private class Pairing(
    val c: DisposableSigner, val s: DisposableSigner,
    clientPin: ByteArray = s.pin, serverPin: ByteArray? = c.pin, clientSigner: Any = c,
) : AutoCloseable {
    val client = tls.open(false, c.spki, clientSigner, clientPin)
    val server = tls.open(true, s.spki, s, serverPin)

    fun hello() = tls.receive(server, tls.outbound(client))

    private fun receiveFinished(receiver: Long, flight: ByteArray) {
        check(flight.isNotEmpty())
        tls.receive(receiver, flight.copyOf(flight.size - 1))
        check(tls.peerPin(receiver) == null && tls.plaintext(receiver).isEmpty())
        tls.receive(receiver, byteArrayOf(flight.last()))
    }

    fun finish() {
        check(tls.peerPin(client) == null && tls.peerPin(server) == null)
        hello()
        receiveFinished(client, tls.outbound(server))
        receiveFinished(server, tls.outbound(client))
        check(tls.peerPin(server)!!.contentEquals(c.pin) && tls.peerPin(client)!!.contentEquals(s.pin))
        check(tls.outbound(server).isEmpty()) // no session tickets
    }

    override fun close() { tls.close(client); tls.close(server) }
}

private fun suite() {
    val c = DisposableSigner(); val s = DisposableSigner()
    val bulk = ByteArray(MAX) { (it * 37).toByte() }
    for (pin in listOf(c.pin, null)) {
        Pairing(c, s, serverPin = pin).use { p ->
            val clientCalls = c.signCount; val serverCalls = s.signCount
            p.finish()
            check(c.signCount == clientCalls + 1 && s.signCount == serverCalls + 1)
            for ((from, to) in listOf(p.client to p.server, p.server to p.client)) {
                tls.send(from, bulk)
                val wire = tls.outbound(from)
                check(wire.size > bulk.size && tls.outbound(from).isEmpty())
                tls.receive(to, wire)
                check(tls.plaintext(to).contentEquals(bulk) && tls.plaintext(to).isEmpty())
            }
        }
    }
    Pairing(c, s, clientPin = c.pin).use { p ->
        p.hello(); rejected { tls.receive(p.client, tls.outbound(p.server)) }; unusable(p.client)
    }
    Pairing(c, s, serverPin = s.pin).use { p ->
        p.hello(); tls.receive(p.client, tls.outbound(p.server))
        rejected { tls.receive(p.server, tls.outbound(p.client)) }; unusable(p.server)
    }
    Pairing(c, s, serverPin = null, clientSigner = s).use { p ->
        p.hello(); rejected { tls.receive(p.client, tls.outbound(p.server)) }; unusable(p.client)
        check(tls.peerPin(p.server) == null)
    }
    var owner = 0L
    val reentrantSigner = object {
        fun sign(message: ByteArray): ByteArray {
            rejected { tls.peerPin(owner) }; rejected { tls.close(owner) }
            return c.sign(message)
        }
    }
    Pairing(c, s, clientSigner = reentrantSigner).use { p ->
        owner = p.client
        val task = FutureTask { p.finish() }
        Thread(task).apply { isDaemon = true; start() }
        task.get(10, TimeUnit.SECONDS) // a global ref outlives open's thread/frame; reentry cannot deadlock
    }
    val throwingSigner = object { fun sign(message: ByteArray): ByteArray = throw SecurityException("test signer refused ${message.size} bytes") }
    Pairing(c, s, clientSigner = throwingSigner).use { p ->
        p.hello(); rejected { tls.receive(p.client, tls.outbound(p.server)) }; unusable(p.client)
    }
    rejected { tls.open(false, c.spki, c, null) }
    rejected { tls.open(true, c.spki, c, ByteArray(31)) }
    rejected { tls.open(true, c.spki.copyOf(c.spki.size + 1), c, null) }
    rejected { tls.open(true, ByteArray(91), c, null) }
    val closed = tls.open(false, c.spki, c, s.pin)
    rejected { tls.send(closed, byteArrayOf(1)) }; unusable(closed); tls.close(closed)
    unusable(closed); rejected { tls.close(closed) }
    Pairing(c, s).use { first ->
        first.finish(); tls.send(first.client, byteArrayOf(1, 2)); val firstRecord = tls.outbound(first.client)
        Pairing(c, s).use { second ->
            second.finish(); rejected { tls.receive(second.server, firstRecord) }; unusable(second.server)
        }
        firstRecord[firstRecord.lastIndex] = (firstRecord.last().toInt() xor 1).toByte()
        rejected { tls.receive(first.server, firstRecord) }; unusable(first.server)
    }
    Pairing(c, s).use { p ->
        p.finish(); tls.send(p.client, bulk); tls.receive(p.server, tls.outbound(p.client))
        tls.send(p.client, byteArrayOf(1)); rejected { tls.receive(p.server, tls.outbound(p.client)) }
        unusable(p.server) // pending plaintext discarded, not a partial delivery
        tls.send(p.client, bulk); rejected { tls.send(p.client, bulk) }; unusable(p.client)
    }
    Pairing(c, s).use { p ->
        p.finish(); rejected { tls.receive(p.client, ByteArray(MAX + 65537)) }
        rejected { tls.send(p.server, ByteArray(MAX + 1)) }; unusable(p.server); unusable(p.client)
    }
    val handles = LongArray(32) { tls.open(true, s.spki, s, null) }
    try { rejected { tls.open(true, s.spki, s, null) } } finally { handles.forEach(tls::close) }
    val next = tls.open(true, s.spki, s, null); check(next > handles.last()); tls.close(next)
    println("PASS JNI: pinned + enrollment, fragmented Finished, 1 MiB both ways, fresh signatures, wrong pins/signer, record/session rejection, bounds and handle reuse prevention")
}

// Two different VMs and binaries talk over a local adb pipe. Pin provisioning
// through the same trusted test harness is NOT a production enrollment design.
private fun cross(server: Boolean, input: DataInputStream, output: DataOutputStream) {
    val me = DisposableSigner()
    output.write(me.pin); output.flush()
    val pin = ByteArray(32).also(input::readFully)
    val id = tls.open(server, me.spki, me, pin)
    fun flush() { val buf = tls.outbound(id); output.writeInt(buf.size); output.write(buf); output.flush() }
    fun receive() {
        val n = input.readInt(); check(n in 1..MAX)
        tls.receive(id, ByteArray(n).also(input::readFully))
    }
    val request = ByteArray(38417) { it.toByte() }
    val reply = request.reversedArray()
    try {
        if (server) {
            receive(); flush(); receive()
            check(tls.peerPin(id)!!.contentEquals(pin))
            receive(); check(tls.plaintext(id).contentEquals(request)); tls.send(id, reply); flush()
        } else {
            flush(); receive(); flush(); check(tls.peerPin(id)!!.contentEquals(pin))
            tls.send(id, request); flush(); receive(); check(tls.plaintext(id).contentEquals(reply))
        }
        check(me.signCount == 1)
    } finally { tls.close(id) }
}

fun main(args: Array<String>) {
    System.load(args[0])
    when (args[1]) {
        "suite" -> suite()
        "peer" -> cross(args[2] == "server", DataInputStream(FileInputStream(FileDescriptor.`in`)), DataOutputStream(FileOutputStream(FileDescriptor.out)))
        "cross" -> {
            val proc = ProcessBuilder(args.drop(3)).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            try {
                cross(args[2] == "server", DataInputStream(proc.inputStream), DataOutputStream(proc.outputStream))
                proc.outputStream.close(); check(proc.waitFor() == 0)
            } finally { proc.destroy() }
            println("PASS cross-VM JNI: desktop ${args[2]}, mutual pins, request and reply 38417 bytes each")
        }
        else -> error("unknown test mode")
    }
}
