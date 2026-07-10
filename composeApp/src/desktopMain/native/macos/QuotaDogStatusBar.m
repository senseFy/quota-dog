#import <Cocoa/Cocoa.h>

typedef void (*QDActionCallback)(void);

static const CGFloat QDPanelWidth = 420.0;
static const CGFloat QDPanelHeight = 580.0;

@interface QDStatusBarController : NSObject <NSPopoverDelegate>
@property(nonatomic, strong) NSStatusItem *statusItem;
@property(nonatomic, strong) NSPopover *popover;
@property(nonatomic, copy) NSDictionary *state;
@property(nonatomic, strong) id localEventMonitor;
@property(nonatomic, strong) id globalEventMonitor;
@property(nonatomic, strong) id resignActiveObserver;
@property(nonatomic, assign) QDActionCallback onRefresh;
@property(nonatomic, assign) QDActionCallback onOpenHide;
@property(nonatomic, assign) QDActionCallback onQuit;
@end

@implementation QDStatusBarController

- (instancetype)initWithRefresh:(QDActionCallback)refresh
                       openHide:(QDActionCallback)openHide
                           quit:(QDActionCallback)quit {
    self = [super init];
    if (!self) return nil;

    _onRefresh = refresh;
    _onOpenHide = openHide;
    _onQuit = quit;
    _state = @{};

    _statusItem = [[NSStatusBar systemStatusBar] statusItemWithLength:NSVariableStatusItemLength];
    NSStatusBarButton *button = _statusItem.button;
    button.title = @"Q";
    button.font = [NSFont boldSystemFontOfSize:13.0];
    button.target = self;
    button.action = @selector(togglePopover:);
    button.toolTip = @"QuotaDog";

    _popover = [[NSPopover alloc] init];
    _popover.behavior = NSPopoverBehaviorTransient;
    _popover.animates = YES;
    _popover.contentSize = NSMakeSize(QDPanelWidth, QDPanelHeight);
    _popover.delegate = self;
    return self;
}

- (void)dealloc {
    [self stopDismissMonitoring];
    if (_statusItem) {
        [[NSStatusBar systemStatusBar] removeStatusItem:_statusItem];
    }
}

- (void)updateWithJSONString:(NSString *)jsonString {
    NSData *data = [jsonString dataUsingEncoding:NSUTF8StringEncoding];
    NSDictionary *decoded = nil;
    if (data) {
        id value = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
        if ([value isKindOfClass:[NSDictionary class]]) {
            decoded = (NSDictionary *)value;
        }
    }
    self.state = decoded ?: @{};
    NSString *tooltip = [self stringForKey:@"tooltip" fallback:@"QuotaDog"];
    self.statusItem.button.toolTip = tooltip;
    if (self.popover.isShown) {
        [self rebuildPopoverContent];
    }
}

- (void)togglePopover:(id)sender {
    if (self.popover.isShown) {
        [self closePopover:sender];
        return;
    }

    [self rebuildPopoverContent];
    NSView *anchor = self.statusItem.button;
    [NSApp activateIgnoringOtherApps:YES];
    [self.popover showRelativeToRect:anchor.bounds ofView:anchor preferredEdge:NSRectEdgeMinY];
    [self.popover.contentViewController.view.window makeKeyWindow];
    [self startDismissMonitoring];
}

- (void)refreshClicked:(id)sender {
    if (self.onRefresh) self.onRefresh();
}

- (void)openHideClicked:(id)sender {
    [self closePopover:sender];
    if (self.onOpenHide) self.onOpenHide();
}

- (void)quitClicked:(id)sender {
    [self closePopover:sender];
    if (self.onQuit) self.onQuit();
}

- (void)closePopover:(id)sender {
    [self.popover performClose:sender];
}

- (void)popoverDidClose:(NSNotification *)notification {
    [self stopDismissMonitoring];
}

- (void)startDismissMonitoring {
    if (self.localEventMonitor || self.globalEventMonitor || self.resignActiveObserver) {
        return;
    }

    __weak typeof(self) weakSelf = self;
    NSEventMask mouseMask = NSEventMaskLeftMouseDown | NSEventMaskRightMouseDown | NSEventMaskOtherMouseDown;

    self.localEventMonitor = [NSEvent addLocalMonitorForEventsMatchingMask:mouseMask handler:^NSEvent *(NSEvent *event) {
        QDStatusBarController *strongSelf = weakSelf;
        if (!strongSelf || !strongSelf.popover.isShown) {
            return event;
        }
        if ([strongSelf eventIsInsideStatusButton:event] || [strongSelf eventIsInsidePopover:event]) {
            return event;
        }
        [strongSelf closePopover:event];
        return event;
    }];

    self.globalEventMonitor = [NSEvent addGlobalMonitorForEventsMatchingMask:mouseMask handler:^(NSEvent *event) {
        QDStatusBarController *strongSelf = weakSelf;
        if (!strongSelf || !strongSelf.popover.isShown) {
            return;
        }
        [strongSelf closePopover:event];
    }];

    self.resignActiveObserver = [[NSNotificationCenter defaultCenter]
        addObserverForName:NSApplicationWillResignActiveNotification
                    object:NSApp
                     queue:[NSOperationQueue mainQueue]
                usingBlock:^(__unused NSNotification *notification) {
                    QDStatusBarController *strongSelf = weakSelf;
                    if (strongSelf.popover.isShown) {
                        [strongSelf closePopover:nil];
                    }
                }];
}

- (void)stopDismissMonitoring {
    if (self.localEventMonitor) {
        [NSEvent removeMonitor:self.localEventMonitor];
        self.localEventMonitor = nil;
    }
    if (self.globalEventMonitor) {
        [NSEvent removeMonitor:self.globalEventMonitor];
        self.globalEventMonitor = nil;
    }
    if (self.resignActiveObserver) {
        [[NSNotificationCenter defaultCenter] removeObserver:self.resignActiveObserver];
        self.resignActiveObserver = nil;
    }
}

- (BOOL)eventIsInsideStatusButton:(NSEvent *)event {
    NSStatusBarButton *button = self.statusItem.button;
    if (!button || event.window != button.window) {
        return NO;
    }
    NSPoint pointInButton = [button convertPoint:event.locationInWindow fromView:nil];
    return NSPointInRect(pointInButton, button.bounds);
}

- (BOOL)eventIsInsidePopover:(NSEvent *)event {
    NSWindow *popoverWindow = self.popover.contentViewController.view.window;
    return popoverWindow && event.window == popoverWindow;
}

- (void)rebuildPopoverContent {
    NSView *root = [[NSView alloc] initWithFrame:NSMakeRect(0, 0, QDPanelWidth, QDPanelHeight)];
    CGFloat y = QDPanelHeight - 42.0;

    [self addLabel:@"QuotaDog" to:root frame:NSMakeRect(20, y, 260, 24) fontSize:20 bold:YES secondary:NO alignRight:NO];
    y -= 20.0;
    [self addLabel:[self stringForKey:@"summary" fallback:@"No accounts yet"]
                to:root
             frame:NSMakeRect(20, y, 360, 18)
          fontSize:12
              bold:NO
         secondary:YES
        alignRight:NO];
    y -= 26.0;

    NSArray *accounts = [self arrayForKey:@"accounts"];
    if (accounts.count == 0) {
        [self addLabel:@"No usage data yet" to:root frame:NSMakeRect(20, y - 54, 360, 24) fontSize:16 bold:YES secondary:NO alignRight:NO];
        [self addLabel:@"Add an account in QuotaDog to show live usage here."
                    to:root
                 frame:NSMakeRect(20, y - 78, 360, 20)
              fontSize:12
                  bold:NO
             secondary:YES
            alignRight:NO];
    } else {
        NSUInteger count = MIN(accounts.count, 4);
        for (NSUInteger i = 0; i < count; i++) {
            if (y < 118.0) break;
            NSDictionary *account = [accounts[i] isKindOfClass:[NSDictionary class]] ? accounts[i] : @{};
            [self addLabel:[self stringIn:account key:@"title" fallback:@"Account"]
                        to:root
                     frame:NSMakeRect(20, y, 360, 20)
                  fontSize:14
                      bold:YES
                 secondary:NO
                alignRight:NO];
            y -= 18.0;
            [self addLabel:[self stringIn:account key:@"status" fallback:@"No usage data yet"]
                        to:root
                     frame:NSMakeRect(20, y, 360, 16)
                  fontSize:11
                      bold:NO
                 secondary:YES
                alignRight:NO];
            y -= 22.0;

            NSArray *windows = [account[@"windows"] isKindOfClass:[NSArray class]] ? account[@"windows"] : @[];
            if (windows.count == 0) {
                [self addLabel:@"No usage data yet" to:root frame:NSMakeRect(28, y, 340, 16) fontSize:11 bold:NO secondary:YES alignRight:NO];
                y -= 24.0;
            } else {
                NSUInteger windowCount = MIN(windows.count, 3);
                for (NSUInteger j = 0; j < windowCount; j++) {
                    if (y < 112.0) break;
                    NSDictionary *window = [windows[j] isKindOfClass:[NSDictionary class]] ? windows[j] : @{};
                    NSInteger usedPct = [self integerIn:window key:@"usedPct" fallback:0];
                    NSInteger remainingPct = [self integerIn:window key:@"remainingPct" fallback:0];
                    NSString *resetLabel = [self stringIn:window key:@"resetLabel" fallback:@"—"];
                    [self addLabel:[self stringIn:window key:@"label" fallback:@"Usage"]
                                to:root
                             frame:NSMakeRect(28, y, 180, 16)
                          fontSize:11
                              bold:NO
                         secondary:NO
                        alignRight:NO];
                    [self addLabel:[NSString stringWithFormat:@"%ld%% used", (long)usedPct]
                                to:root
                             frame:NSMakeRect(254, y, 126, 16)
                          fontSize:11
                              bold:NO
                         secondary:NO
                        alignRight:YES];
                    y -= 10.0;
                    [self addProgressTo:root frame:NSMakeRect(28, y, 352, 8) value:usedPct];
                    y -= 16.0;
                    [self addLabel:[NSString stringWithFormat:@"%ld%% left · resets %@", (long)remainingPct, resetLabel]
                                to:root
                             frame:NSMakeRect(28, y, 352, 14)
                          fontSize:10
                              bold:NO
                         secondary:YES
                        alignRight:NO];
                    y -= 20.0;
                }
            }
            y -= 8.0;
        }

        NSInteger moreAccounts = [self integerForKey:@"moreAccounts" fallback:0];
        if (moreAccounts > 0 && y >= 100.0) {
            [self addLabel:[NSString stringWithFormat:@"+%ld more accounts in QuotaDog", (long)moreAccounts]
                        to:root
                     frame:NSMakeRect(20, y, 360, 18)
                  fontSize:11
                      bold:NO
                 secondary:YES
                alignRight:NO];
        }
    }

    [self addButton:@"Refresh" to:root frame:NSMakeRect(20, 20, 110, 30) action:@selector(refreshClicked:) enabled:[self boolForKey:@"refreshEnabled"]];
    NSString *openHideTitle = [self boolForKey:@"windowVisible"] ? @"Hide app" : @"Open app";
    [self addButton:openHideTitle to:root frame:NSMakeRect(146, 20, 110, 30) action:@selector(openHideClicked:) enabled:YES];
    [self addButton:@"Quit" to:root frame:NSMakeRect(272, 20, 110, 30) action:@selector(quitClicked:) enabled:YES];

    NSViewController *controller = [[NSViewController alloc] init];
    controller.view = root;
    self.popover.contentSize = NSMakeSize(QDPanelWidth, QDPanelHeight);
    self.popover.contentViewController = controller;
}

- (void)addLabel:(NSString *)text
              to:(NSView *)parent
           frame:(NSRect)frame
        fontSize:(CGFloat)fontSize
            bold:(BOOL)bold
       secondary:(BOOL)secondary
      alignRight:(BOOL)alignRight {
    NSTextField *label = [NSTextField labelWithString:text ?: @""];
    label.frame = frame;
    label.font = bold ? [NSFont boldSystemFontOfSize:fontSize] : [NSFont systemFontOfSize:fontSize];
    label.textColor = secondary ? [NSColor secondaryLabelColor] : [NSColor labelColor];
    label.lineBreakMode = NSLineBreakByTruncatingTail;
    label.usesSingleLineMode = YES;
    label.alignment = alignRight ? NSTextAlignmentRight : NSTextAlignmentLeft;
    [parent addSubview:label];
}

- (void)addProgressTo:(NSView *)parent frame:(NSRect)frame value:(NSInteger)value {
    NSProgressIndicator *progress = [[NSProgressIndicator alloc] initWithFrame:frame];
    progress.indeterminate = NO;
    progress.minValue = 0.0;
    progress.maxValue = 100.0;
    progress.doubleValue = MAX(0, MIN(100, value));
    [parent addSubview:progress];
}

- (void)addButton:(NSString *)title to:(NSView *)parent frame:(NSRect)frame action:(SEL)action enabled:(BOOL)enabled {
    NSButton *button = [NSButton buttonWithTitle:title target:self action:action];
    button.frame = frame;
    button.bezelStyle = NSBezelStyleRounded;
    button.enabled = enabled;
    [parent addSubview:button];
}

- (NSString *)stringForKey:(NSString *)key fallback:(NSString *)fallback {
    return [self stringIn:self.state key:key fallback:fallback];
}

- (NSString *)stringIn:(NSDictionary *)dictionary key:(NSString *)key fallback:(NSString *)fallback {
    id value = dictionary[key];
    return [value isKindOfClass:[NSString class]] ? value : fallback;
}

- (NSArray *)arrayForKey:(NSString *)key {
    id value = self.state[key];
    return [value isKindOfClass:[NSArray class]] ? value : @[];
}

- (NSInteger)integerForKey:(NSString *)key fallback:(NSInteger)fallback {
    return [self integerIn:self.state key:key fallback:fallback];
}

- (NSInteger)integerIn:(NSDictionary *)dictionary key:(NSString *)key fallback:(NSInteger)fallback {
    id value = dictionary[key];
    return [value respondsToSelector:@selector(integerValue)] ? [value integerValue] : fallback;
}

- (BOOL)boolForKey:(NSString *)key {
    id value = self.state[key];
    return [value respondsToSelector:@selector(boolValue)] ? [value boolValue] : NO;
}

@end

void *qd_statusbar_create(QDActionCallback onRefresh, QDActionCallback onOpenHide, QDActionCallback onQuit) {
    __block QDStatusBarController *controller = nil;
    void (^create)(void) = ^{
        controller = [[QDStatusBarController alloc] initWithRefresh:onRefresh openHide:onOpenHide quit:onQuit];
    };
    if ([NSThread isMainThread]) {
        create();
    } else {
        dispatch_sync(dispatch_get_main_queue(), create);
    }
    return (__bridge_retained void *)controller;
}

void qd_statusbar_update(void *handle, const char *json) {
    if (!handle) return;
    QDStatusBarController *controller = (__bridge QDStatusBarController *)handle;
    NSString *jsonString = json ? [NSString stringWithUTF8String:json] : @"{}";
    dispatch_async(dispatch_get_main_queue(), ^{
        [controller updateWithJSONString:jsonString ?: @"{}"];
    });
}

void qd_statusbar_dispose(void *handle) {
    if (!handle) return;
    QDStatusBarController *controller = (__bridge_transfer QDStatusBarController *)handle;
    dispatch_async(dispatch_get_main_queue(), ^{
        [controller.popover performClose:nil];
        [[NSStatusBar systemStatusBar] removeStatusItem:controller.statusItem];
        controller.statusItem = nil;
    });
}
