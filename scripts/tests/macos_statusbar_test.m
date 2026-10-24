#import <AppKit/AppKit.h>

typedef void (*QDActionCallback)(void);
typedef void (*QDProviderCallback)(const char *);
typedef void (*QDAccountCallback)(const char *);
extern void *qd_statusbar_create(QDActionCallback, QDActionCallback, QDActionCallback,
                                QDActionCallback, QDProviderCallback, QDAccountCallback);
extern void qd_statusbar_dispose(void *);

// JNI/JNA calls arrive from the AWT thread. Exercise the same cross-thread
// lifecycle against AppKit: releasing the controller off-main crashes macOS.
int main(void) {
    @autoreleasepool {
        [NSApplication sharedApplication];
        [NSApp setActivationPolicy:NSApplicationActivationPolicyProhibited];
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_DEFAULT, 0), ^{
            for (int attempt = 0; attempt < 3; attempt++) {
                void *handle = qd_statusbar_create(NULL, NULL, NULL, NULL, NULL, NULL);
                if (!handle) exit(1);
                qd_statusbar_dispose(handle);
            }
            dispatch_async(dispatch_get_main_queue(), ^{
                puts("PASS status bar creation and final release from a worker thread");
                [NSApp terminate:nil];
            });
        });
        [NSApp run];
    }
    return 0;
}
