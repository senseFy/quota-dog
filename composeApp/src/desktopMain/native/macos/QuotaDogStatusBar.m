#import <Cocoa/Cocoa.h>

typedef void (*QDActionCallback)(void);
typedef void (*QDProviderCallback)(const char *provider);

// Set to 1 to paint layout containers in loud colors and log sizes.
#define QD_DEBUG_LAYOUT 0

#if QD_DEBUG_LAYOUT
static NSString * const QDStatusBarBuildId = @"panel-v6";

static void QDLog(NSString *format, ...) NS_FORMAT_FUNCTION(1, 2);
static void QDLog(NSString *format, ...) {
    va_list args;
    va_start(args, format);
    NSString *message = [[NSString alloc] initWithFormat:format arguments:args];
    va_end(args);
    NSLog(@"[QDStatusBar] %@", message);
}
#else
#define QDLog(...) ((void)0)
#endif

static const CGFloat QDPanelWidth = 420.0;
static const CGFloat QDPanelMaxHeight = 800.0;
static const CGFloat QDOuterPad = 20.0;
static const CGFloat QDCardRadius = 18.0;
static const CGFloat QDCardPad = 12.0;
static const CGFloat QDAvatarSize = 28.0;
static const CGFloat QDProgressHeight = 6.0;
static const CGFloat QDButtonHeight = 28.0;
static const CGFloat QDAccountGap = 12.0;
static const CGFloat QDSectionGap = 12.0;
static const CGFloat QDFooterBottomPad = 10.0;
static const CGFloat QDEmptyContentHeight = 96.0;
static const NSUInteger QDMaxAccounts = 4;
static const NSUInteger QDMaxWindows = 3;

#pragma mark - Design tokens (mirrors QdTheme light / dark)

typedef struct {
    NSColor *backgroundElevated;
    NSColor *surface;
    NSColor *surfaceMuted;
    NSColor *surfaceHover;
    NSColor *border;
    NSColor *textPrimary;
    NSColor *textSecondary;
    NSColor *textTertiary;
    NSColor *primary;
    NSColor *primaryHover;
    NSColor *primaryPressed;
    NSColor *onPrimary;
    NSColor *success;
    NSColor *warning;
    NSColor *danger;
    NSColor *codexAccent;
    NSColor *claudeAccent;
    NSColor *grokAccent;
} QDPalette;

static NSColor *QDHex(unsigned int hex) {
    CGFloat r = ((hex >> 16) & 0xFF) / 255.0;
    CGFloat g = ((hex >> 8) & 0xFF) / 255.0;
    CGFloat b = (hex & 0xFF) / 255.0;
    return [NSColor colorWithSRGBRed:r green:g blue:b alpha:1.0];
}

static QDPalette QDLightPalette(void) {
    return (QDPalette){
        .backgroundElevated = QDHex(0xFAFCFA),
        .surface = QDHex(0xFFFFFF),
        .surfaceMuted = QDHex(0xF1F5F2),
        .surfaceHover = QDHex(0xEEF3EF),
        .border = QDHex(0xE3EAE5),
        .textPrimary = QDHex(0x0F1F17),
        .textSecondary = QDHex(0x4D6359),
        .textTertiary = QDHex(0x859089),
        .primary = QDHex(0x2F7D5B),
        .primaryHover = QDHex(0x286A4D),
        .primaryPressed = QDHex(0x1F5840),
        .onPrimary = QDHex(0xF6FBF8),
        .success = QDHex(0x2F7D5B),
        .warning = QDHex(0xB76E1B),
        .danger = QDHex(0xB94545),
        .codexAccent = QDHex(0x1B2A24),
        .claudeAccent = QDHex(0xB75C2C),
        .grokAccent = QDHex(0x111111),
    };
}

static QDPalette QDDarkPalette(void) {
    return (QDPalette){
        .backgroundElevated = QDHex(0x161E1A),
        .surface = QDHex(0x1A2520),
        .surfaceMuted = QDHex(0x202C26),
        .surfaceHover = QDHex(0x26342D),
        .border = QDHex(0x2C3833),
        .textPrimary = QDHex(0xE6EFEA),
        .textSecondary = QDHex(0xA6B3AC),
        .textTertiary = QDHex(0x6F7E77),
        .primary = QDHex(0x6FBE99),
        .primaryHover = QDHex(0x7FCAA6),
        .primaryPressed = QDHex(0x5BA984),
        .onPrimary = QDHex(0x0F1F17),
        .success = QDHex(0x6FBE99),
        .warning = QDHex(0xD89757),
        .danger = QDHex(0xE07878),
        .codexAccent = QDHex(0xD7DBD8),
        .claudeAccent = QDHex(0xE89368),
        .grokAccent = QDHex(0xE6E6E6),
    };
}

static BOOL QDIsDarkAppearance(void) {
    NSAppearance *appearance = NSApp.effectiveAppearance;
    NSAppearanceName name = [appearance bestMatchFromAppearancesWithNames:@[
        NSAppearanceNameAqua,
        NSAppearanceNameDarkAqua,
    ]];
    return [name isEqualToString:NSAppearanceNameDarkAqua];
}

static BOOL QDResolveDarkTheme(NSDictionary *state) {
    id value = state[@"darkTheme"];
    if ([value respondsToSelector:@selector(boolValue)]) {
        return [value boolValue];
    }
    return QDIsDarkAppearance();
}

static QDPalette QDPaletteForDark(BOOL dark) {
    return dark ? QDDarkPalette() : QDLightPalette();
}

static NSColor *QDUsageFill(NSInteger usedPct, QDPalette palette) {
    if (usedPct >= 90) return palette.danger;
    if (usedPct >= 70) return palette.warning;
    return palette.success;
}

static NSColor *QDProviderAccent(NSString *provider, QDPalette palette) {
    if ([provider isEqualToString:@"CLAUDE_CODE"]) return palette.claudeAccent;
    if ([provider isEqualToString:@"GROK"]) return palette.grokAccent;
    return palette.codexAccent;
}

// Path data from composeResources/drawable/provider_*.xml (lobehub icons-static-svg 1.88.0).
static NSString * const kQDPathCodex =
    @"M8.086,0.457a6.105,6.105 0,0 1,3.046 -0.415c1.333,0.153 2.521,0.72 3.564,1.7a0.117,0.117 0,0 0,0.107 0.029c1.408,-0.346 2.762,-0.224 4.061,0.366l0.063,0.03l0.154,0.076c1.357,0.703 2.33,1.77 2.918,3.198c0.278,0.679 0.418,1.388 0.421,2.126a5.655,5.655 0,0 1,-0.18 1.631a0.167,0.167 0,0 0,0.04 0.155a5.982,5.982 0,0 1,1.578 2.891c0.385,1.901 -0.01,3.615 -1.183,5.14l-0.182,0.22a6.063,6.063 0,0 1,-2.934 1.851a0.162,0.162 0,0 0,-0.108 0.102c-0.255,0.736 -0.511,1.364 -0.987,1.992c-1.199,1.582 -2.962,2.462 -4.948,2.451c-1.583,-0.008 -2.986,-0.587 -4.21,-1.736a0.145,0.145 0,0 0,-0.14 -0.032c-0.518,0.167 -1.04,0.191 -1.604,0.185a5.924,5.924 0,0 1,-2.595 -0.622a6.058,6.058 0,0 1,-2.146 -1.781c-0.203,-0.269 -0.404,-0.522 -0.551,-0.821a7.74,7.74 0,0 1,-0.495 -1.283a6.11,6.11 0,0 1,-0.017 -3.064a0.166,0.166 0,0 0,0.008 -0.074a0.115,0.115 0,0 0,-0.037 -0.064a5.958,5.958 0,0 1,-1.38 -2.202a5.196,5.196 0,0 1,-0.333 -1.589a6.915,6.915 0,0 1,0.188 -2.132c0.45,-1.484 1.309,-2.648 2.577,-3.493c0.282,-0.188 0.55,-0.334 0.802,-0.438c0.286,-0.12 0.573,-0.22 0.861,-0.304a0.129,0.129 0,0 0,0.087 -0.087A6.016,6.016 0,0 1,5.635 2.31C6.315,1.464 7.132,0.846 8.086,0.457zM7.282,8.307a0.848,0.848 0,0 0,-1.473 0.842l1.694,2.965l-1.688,2.848a0.849,0.849 0,0 0,1.46 0.864l1.94,-3.272a0.849,0.849 0,0 0,0.007 -0.854L7.282,8.307zM12.728,14.547a0.849,0.849 0,0 0,0 1.695h4.848a0.849,0.849 0,0 0,0 -1.696H12.728z";

static NSString * const kQDPathClaudeCode =
    @"M20.998,10.949H24v3.102h-3v3.028h-1.487V20H18v-2.921h-1.487V20H15v-2.921H9V20H7.488v-2.921H6V20H4.487v-2.921H3V14.05H0v-3.101h3V5h17.998v5.949zM6,10.949h1.488V8.102H6v2.847zM16.51,10.949H18V8.102h-1.49v2.847z";

static NSString * const kQDPathGrok =
    @"M9.27,15.29l7.978,-5.897c0.391,-0.29 0.95,-0.177 1.137,0.272c0.98,2.369 0.542,5.215 -1.41,7.169c-1.951,1.954 -4.667,2.382 -7.149,1.406l-2.711,1.257c3.889,2.661 8.611,2.003 11.562,-0.953c2.341,-2.344 3.066,-5.539 2.388,-8.42l0.006,0.007c-0.983,-4.232 0.242,-5.924 2.75,-9.383c0.06,-0.082 0.12,-0.164 0.179,-0.248l-3.301,3.305v-0.01L9.267,15.292M7.623,16.723c-2.792,-2.67 -2.31,-6.801 0.071,-9.184c1.761,-1.763 4.647,-2.483 7.166,-1.425l2.705,-1.25a7.808,7.808 0,0 0,-1.829 -1A8.975,8.975 0,0 0,5.984 5.83c-2.533,2.536 -3.33,6.436 -1.962,9.764c1.022,2.487 -0.653,4.246 -2.34,6.022c-0.599,0.63 -1.199,1.259 -1.682,1.925l7.62,-6.815";

/** Icon scale inside the 28pt avatar slot — mirrors QdProviderAvatar. */
static CGFloat QDProviderIconScale(NSString *provider) {
    if ([provider isEqualToString:@"CLAUDE_CODE"]) return 0.76;
    if ([provider isEqualToString:@"GROK"]) return 0.76;
    return 0.70; // CODEX
}

static NSString *QDProviderPathData(NSString *provider) {
    if ([provider isEqualToString:@"CLAUDE_CODE"]) return kQDPathClaudeCode;
    if ([provider isEqualToString:@"GROK"]) return kQDPathGrok;
    return kQDPathCodex;
}

/** Render monochrome vector path to a bitmap, then tint (same pipeline as Compose ColorFilter.tint). */
static NSImage *QDProviderLogoImage(NSString *provider, NSColor *tint, CGFloat pointSize) {
    static NSCache *cache;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ cache = [[NSCache alloc] init]; });

    CGFloat scale = NSScreen.mainScreen.backingScaleFactor ?: 2.0;
    NSInteger px = (NSInteger)ceil(pointSize * scale);
    NSString *key = [NSString stringWithFormat:@"%@|%@|%ld",
                     provider ?: @"CODEX",
                     tint.description ?: @"",
                     (long)px];
    NSImage *cached = [cache objectForKey:key];
    if (cached) return cached;

    NSString *pathData = QDProviderPathData(provider);
    NSString *svg = [NSString stringWithFormat:
        @"<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        @"<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%ld\" height=\"%ld\" viewBox=\"0 0 24 24\">"
        @"<path fill=\"#000000\" fill-rule=\"evenodd\" d=\"%@\"/>"
        @"</svg>",
        (long)px, (long)px, pathData];

    NSData *svgData = [svg dataUsingEncoding:NSUTF8StringEncoding];
    NSImage *source = [[NSImage alloc] initWithData:svgData];
    if (!source) return nil;
    source.size = NSMakeSize(pointSize, pointSize);

    NSBitmapImageRep *rep = [[NSBitmapImageRep alloc]
        initWithBitmapDataPlanes:NULL
                      pixelsWide:px
                      pixelsHigh:px
                   bitsPerSample:8
                 samplesPerPixel:4
                        hasAlpha:YES
                        isPlanar:NO
                  colorSpaceName:NSCalibratedRGBColorSpace
                     bytesPerRow:0
                    bitsPerPixel:0];

    NSGraphicsContext *ctx = [NSGraphicsContext graphicsContextWithBitmapImageRep:rep];
    [NSGraphicsContext saveGraphicsState];
    [NSGraphicsContext setCurrentContext:ctx];
    ctx.imageInterpolation = NSImageInterpolationHigh;
    [[NSColor clearColor] set];
    NSRectFill(NSMakeRect(0, 0, px, px));
    [source drawInRect:NSMakeRect(0, 0, px, px)
              fromRect:NSZeroRect
             operation:NSCompositingOperationSourceOver
              fraction:1.0
        respectFlipped:YES
                 hints:@{NSImageHintInterpolation: @(NSImageInterpolationHigh)}];
    // Tint: keep glyph alpha, replace RGB with provider accent (Compose ColorFilter.tint).
    [tint set];
    NSRectFillUsingOperation(NSMakeRect(0, 0, px, px), NSCompositingOperationSourceIn);
    [NSGraphicsContext restoreGraphicsState];

    NSImage *image = [[NSImage alloc] initWithSize:NSMakeSize(pointSize, pointSize)];
    [image addRepresentation:rep];
    [cache setObject:image forKey:key];
    return image;
}

#pragma mark - Views

/** Top-left origin container so layout math matches Compose / CSS. */
@interface QDFlippedView : NSView
@end
@implementation QDFlippedView
- (BOOL)isFlipped { return YES; }
@end

@interface QDFillView : QDFlippedView
@property(nonatomic, strong) NSColor *fillColor;
@property(nonatomic, strong) NSColor *borderColor;
@property(nonatomic, assign) CGFloat cornerRadius;
@end

@implementation QDFillView
- (instancetype)initWithFrame:(NSRect)frame {
    self = [super initWithFrame:frame];
    if (self) {
        self.wantsLayer = YES;
        _cornerRadius = 0;
        self.autoresizingMask = NSViewNotSizable;
    }
    return self;
}

- (void)updateLayerStyle {
    self.wantsLayer = YES;
    self.layer.backgroundColor = self.fillColor.CGColor;
    self.layer.cornerRadius = self.cornerRadius;
    self.layer.masksToBounds = YES;
    if (self.borderColor) {
        self.layer.borderWidth = 1.0;
        self.layer.borderColor = self.borderColor.CGColor;
    } else {
        self.layer.borderWidth = 0;
    }
}

- (void)setFillColor:(NSColor *)fillColor {
    _fillColor = fillColor;
    [self updateLayerStyle];
}

- (void)setBorderColor:(NSColor *)borderColor {
    _borderColor = borderColor;
    [self updateLayerStyle];
}

- (void)setCornerRadius:(CGFloat)cornerRadius {
    _cornerRadius = cornerRadius;
    [self updateLayerStyle];
}

- (void)viewDidChangeEffectiveAppearance {
    [super viewDidChangeEffectiveAppearance];
    [self updateLayerStyle];
}
@end

@interface QDProgressBar : NSView
@property(nonatomic, assign) CGFloat progress; // 0...1
@property(nonatomic, strong) NSColor *trackColor;
@property(nonatomic, strong) NSColor *fillColor;
@end

@implementation QDProgressBar
- (instancetype)initWithFrame:(NSRect)frame {
    self = [super initWithFrame:frame];
    if (self) {
        self.wantsLayer = YES;
        _progress = 0;
    }
    return self;
}

- (BOOL)isFlipped { return YES; }

- (void)drawRect:(NSRect)dirtyRect {
    NSRect bounds = self.bounds;
    CGFloat radius = bounds.size.height / 2.0;
    NSBezierPath *track = [NSBezierPath bezierPathWithRoundedRect:bounds xRadius:radius yRadius:radius];
    [self.trackColor setFill];
    [track fill];

    CGFloat p = MAX(0.0, MIN(1.0, self.progress));
    if (p <= 0.0) return;

    CGFloat minWidth = bounds.size.height;
    CGFloat width = MAX(minWidth, bounds.size.width * p);
    width = MIN(width, bounds.size.width);
    NSRect fillRect = NSMakeRect(0, 0, width, bounds.size.height);
    NSBezierPath *fill = [NSBezierPath bezierPathWithRoundedRect:fillRect xRadius:radius yRadius:radius];
    [self.fillColor setFill];
    [fill fill];
}
@end

typedef NS_ENUM(NSInteger, QDButtonStyle) {
    QDButtonStylePrimary,
    QDButtonStyleSecondary,
    QDButtonStyleGhost,
};

// Plain NSControl — NSButton custom drawing is unreliable inside layer-backed panels.
@interface QDPillButton : NSControl
@property(nonatomic, assign) QDButtonStyle qdStyle;
@property(nonatomic, copy) NSString *title;
@property(nonatomic, strong) NSImage *image;
@property(nonatomic, strong) NSFont *titleFont;
@property(nonatomic, strong) NSColor *restBg;
@property(nonatomic, strong) NSColor *hoverBg;
@property(nonatomic, strong) NSColor *pressedBg;
@property(nonatomic, strong) NSColor *fgColor;
@property(nonatomic, strong) NSColor *borderColor;
@property(nonatomic, assign) BOOL hovering;
@property(nonatomic, assign) BOOL pressed;
@property(nonatomic, copy) NSString *providerIdentifier;
+ (instancetype)buttonWithTitle:(NSString *)title
                          style:(QDButtonStyle)style
                        palette:(QDPalette)palette
                         target:(id)target
                         action:(SEL)action;
+ (instancetype)iconButtonWithSystemSymbol:(NSString *)symbolName
                                   palette:(QDPalette)palette
                                    target:(id)target
                                    action:(SEL)action;
@end

@implementation QDPillButton
- (instancetype)initWithFrame:(NSRect)frame {
    self = [super initWithFrame:frame];
    if (self) {
        self.wantsLayer = YES;
        self.layerContentsRedrawPolicy = NSViewLayerContentsRedrawOnSetNeedsDisplay;
    }
    return self;
}

- (BOOL)isFlipped { return YES; }
- (BOOL)acceptsFirstMouse:(NSEvent *)event { return YES; }
- (BOOL)isOpaque { return NO; }

+ (instancetype)buttonWithTitle:(NSString *)title
                          style:(QDButtonStyle)style
                        palette:(QDPalette)palette
                         target:(id)target
                         action:(SEL)action {
    QDPillButton *button = [[QDPillButton alloc] initWithFrame:NSZeroRect];
    button.title = title ?: @"";
    button.target = target;
    button.action = action;
    button.qdStyle = style;
    button.titleFont = [NSFont systemFontOfSize:12.0 weight:NSFontWeightMedium];

    switch (style) {
        case QDButtonStylePrimary:
            button.restBg = palette.primary;
            button.hoverBg = palette.primaryHover;
            button.pressedBg = palette.primaryPressed;
            button.fgColor = palette.onPrimary;
            button.borderColor = nil;
            break;
        case QDButtonStyleSecondary:
            button.restBg = [NSColor clearColor];
            button.hoverBg = palette.surfaceHover;
            button.pressedBg = palette.surfaceMuted;
            button.fgColor = palette.textSecondary;
            button.borderColor = nil;
            break;
        case QDButtonStyleGhost:
            button.restBg = [NSColor clearColor];
            button.hoverBg = palette.surfaceHover;
            button.pressedBg = palette.surfaceMuted;
            button.fgColor = palette.textTertiary;
            button.borderColor = nil;
            break;
    }
    return button;
}

+ (instancetype)iconButtonWithSystemSymbol:(NSString *)symbolName
                                   palette:(QDPalette)palette
                                    target:(id)target
                                    action:(SEL)action {
    QDPillButton *button = [[QDPillButton alloc] initWithFrame:NSZeroRect];
    button.target = target;
    button.action = action;
    button.qdStyle = QDButtonStyleGhost;
    button.restBg = [NSColor clearColor];
    button.hoverBg = palette.surfaceHover;
    button.pressedBg = palette.surfaceMuted;
    button.fgColor = palette.textTertiary;
    button.borderColor = nil;
    button.title = @"";

    if (@available(macOS 11.0, *)) {
        NSImage *image = [NSImage imageWithSystemSymbolName:symbolName accessibilityDescription:nil];
        if (image) {
            NSImageSymbolConfiguration *config =
                [NSImageSymbolConfiguration configurationWithPointSize:12.0 weight:NSFontWeightMedium];
            image = [image imageWithSymbolConfiguration:config];
            image.template = YES;
            button.image = image;
        }
    }
    return button;
}

- (void)setEnabled:(BOOL)enabled {
    [super setEnabled:enabled];
    [self setNeedsDisplay:YES];
}

- (void)updateTrackingAreas {
    [super updateTrackingAreas];
    for (NSTrackingArea *area in self.trackingAreas) {
        [self removeTrackingArea:area];
    }
    NSTrackingArea *area = [[NSTrackingArea alloc] initWithRect:self.bounds
                                                        options:(NSTrackingMouseEnteredAndExited | NSTrackingActiveAlways | NSTrackingInVisibleRect)
                                                          owner:self
                                                       userInfo:nil];
    [self addTrackingArea:area];
}

- (void)mouseEntered:(NSEvent *)event {
    self.hovering = YES;
    [self setNeedsDisplay:YES];
}

- (void)mouseExited:(NSEvent *)event {
    self.hovering = NO;
    self.pressed = NO;
    [self setNeedsDisplay:YES];
}

- (void)mouseDown:(NSEvent *)event {
    if (!self.enabled) return;
    self.pressed = YES;
    [self setNeedsDisplay:YES];
}

- (void)mouseDragged:(NSEvent *)event {
    if (!self.enabled) return;
    NSPoint loc = [self convertPoint:event.locationInWindow fromView:nil];
    BOOL inside = NSPointInRect(loc, self.bounds);
    if (self.pressed != inside) {
        self.pressed = inside;
        [self setNeedsDisplay:YES];
    }
}

- (void)mouseUp:(NSEvent *)event {
    if (!self.enabled) return;
    BOOL wasPressed = self.pressed;
    self.pressed = NO;
    [self setNeedsDisplay:YES];
    NSPoint loc = [self convertPoint:event.locationInWindow fromView:nil];
    if (wasPressed && NSPointInRect(loc, self.bounds) && self.target && self.action) {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Warc-performSelector-leaks"
        [self.target performSelector:self.action withObject:self];
#pragma clang diagnostic pop
    }
}

- (void)drawRect:(NSRect)dirtyRect {
    NSColor *bg = self.restBg ?: [NSColor clearColor];
    if (!self.enabled) {
        bg = [bg colorWithAlphaComponent:0.5];
    } else if (self.pressed) {
        bg = self.pressedBg ?: bg;
    } else if (self.hovering) {
        bg = self.hoverBg ?: bg;
    }

    CGFloat radius = MIN(self.bounds.size.height, self.bounds.size.width) / 2.0;
    NSBezierPath *path = [NSBezierPath bezierPathWithRoundedRect:NSInsetRect(self.bounds, 0.5, 0.5)
                                                         xRadius:radius
                                                         yRadius:radius];
    [bg setFill];
    [path fill];
    if (self.borderColor) {
        [self.borderColor setStroke];
        path.lineWidth = 1.0;
        [path stroke];
    }

    NSColor *fg = self.enabled ? self.fgColor : [self.fgColor colorWithAlphaComponent:0.55];
    if (self.image) {
        NSImage *source = self.image;
        NSSize size = source.size;
        NSRect imageRect = NSMakeRect(NSMidX(self.bounds) - size.width / 2.0,
                                      NSMidY(self.bounds) - size.height / 2.0,
                                      size.width,
                                      size.height);
        NSImage *tinted = [NSImage imageWithSize:size
                                         flipped:YES
                                  drawingHandler:^BOOL(NSRect dst) {
            [source drawInRect:dst
                      fromRect:NSZeroRect
                     operation:NSCompositingOperationSourceOver
                      fraction:1.0
                respectFlipped:YES
                         hints:nil];
            [fg set];
            NSRectFillUsingOperation(dst, NSCompositingOperationSourceIn);
            return YES;
        }];
        [tinted drawInRect:imageRect
                  fromRect:NSZeroRect
                 operation:NSCompositingOperationSourceOver
                  fraction:1.0
            respectFlipped:YES
                     hints:nil];
    } else if (self.title.length > 0) {
        NSFont *font = self.titleFont ?: [NSFont systemFontOfSize:12.0 weight:NSFontWeightMedium];
        NSDictionary *attrs = @{
            NSFontAttributeName: font,
            NSForegroundColorAttributeName: fg,
        };
        NSAttributedString *attr = [[NSAttributedString alloc] initWithString:self.title attributes:attrs];
        NSSize textSize = [attr size];
        NSPoint textOrigin = NSMakePoint(NSMidX(self.bounds) - textSize.width / 2.0,
                                         NSMidY(self.bounds) - textSize.height / 2.0);
        [attr drawAtPoint:textOrigin];
    }
}
@end

#pragma mark - Controller

@interface QDStatusBarController : NSObject
@property(nonatomic, strong) NSStatusItem *statusItem;
@property(nonatomic, strong) NSPanel *panel;
@property(nonatomic, copy) NSDictionary *state;
@property(nonatomic, assign) NSSize panelSize;
@property(nonatomic, strong) id localEventMonitor;
@property(nonatomic, strong) id globalEventMonitor;
@property(nonatomic, strong) id resignActiveObserver;
@property(nonatomic, strong) id becomeActiveObserver;
@property(nonatomic, strong) id windowBecomeKeyObserver;
@property(nonatomic, assign) QDActionCallback onRefresh;
@property(nonatomic, assign) QDActionCallback onShow;
@property(nonatomic, assign) QDActionCallback onOpenHide;
@property(nonatomic, assign) QDActionCallback onQuit;
@property(nonatomic, assign) QDProviderCallback onSelectProvider;
@property(nonatomic, strong) QDFillView *providerIndicator;
@property(nonatomic, assign) NSUInteger providerSelectionGeneration;
@property(nonatomic, copy) NSString *pendingProviderSelection;
@end

@implementation QDStatusBarController

- (instancetype)initWithRefresh:(QDActionCallback)refresh
                           show:(QDActionCallback)show
                       openHide:(QDActionCallback)openHide
                           quit:(QDActionCallback)quit
                 selectProvider:(QDProviderCallback)selectProvider {
    self = [super init];
    if (!self) return nil;

    _onRefresh = refresh;
    _onShow = show;
    _onOpenHide = openHide;
    _onQuit = quit;
    _onSelectProvider = selectProvider;
    _state = @{};
    _panelSize = NSMakeSize(QDPanelWidth, 200.0);

    _statusItem = [[NSStatusBar systemStatusBar] statusItemWithLength:NSVariableStatusItemLength];
    NSStatusBarButton *button = _statusItem.button;
    button.title = @"Q";
    button.font = [NSFont boldSystemFontOfSize:13.0];
    button.target = self;
    button.action = @selector(togglePanel:);
    button.toolTip = @"QuotaDog";

    // NSPanel instead of NSPopover: popover keeps re-stretching content to a stale max height,
    // which produced the huge empty footer band. Panel contentSize is fully under our control.
    _panel = [[NSPanel alloc] initWithContentRect:NSMakeRect(0, 0, QDPanelWidth, 200)
                                        styleMask:(NSWindowStyleMaskBorderless | NSWindowStyleMaskNonactivatingPanel)
                                          backing:NSBackingStoreBuffered
                                            defer:NO];
    _panel.opaque = NO;
    _panel.backgroundColor = NSColor.clearColor;
    _panel.hasShadow = YES;
    _panel.level = NSPopUpMenuWindowLevel;
    _panel.hidesOnDeactivate = NO;
    _panel.movableByWindowBackground = NO;
    _panel.collectionBehavior =
        NSWindowCollectionBehaviorCanJoinAllSpaces |
        NSWindowCollectionBehaviorFullScreenAuxiliary |
        NSWindowCollectionBehaviorIgnoresCycle;
#if QD_DEBUG_LAYOUT
    QDLog(@"init build=%@ panel=%p", QDStatusBarBuildId, _panel);
#endif
    return self;
}

- (void)dealloc {
    [self stopDismissMonitoring];
    [_panel orderOut:nil];
    if (_statusItem) {
        [[NSStatusBar systemStatusBar] removeStatusItem:_statusItem];
    }
}

- (BOOL)isPanelVisible {
    return self.panel.isVisible;
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
    if (self.pendingProviderSelection && decoded) {
        NSString *decodedSelection = [self stringIn:decoded key:@"selectedProvider" fallback:@""];
        NSArray *decodedFilters = [decoded[@"providerFilters"] isKindOfClass:[NSArray class]]
            ? decoded[@"providerFilters"]
            : @[];
        BOOL pendingStillAvailable = self.pendingProviderSelection.length == 0;
        for (id value in decodedFilters) {
            NSDictionary *filter = [value isKindOfClass:[NSDictionary class]] ? value : @{};
            NSString *identifier = [self stringIn:filter key:@"id" fallback:@""];
            if ([identifier isEqualToString:self.pendingProviderSelection]) {
                pendingStillAvailable = YES;
                break;
            }
        }
        if (!pendingStillAvailable || [decodedSelection isEqualToString:self.pendingProviderSelection]) {
            self.pendingProviderSelection = nil;
            self.state = decoded;
        } else {
            NSMutableDictionary *updatedState = [decoded mutableCopy];
            updatedState[@"selectedProvider"] = self.pendingProviderSelection;
            self.state = updatedState;
        }
    } else {
        self.state = decoded ?: @{};
    }
    NSString *tooltip = [self stringForKey:@"tooltip" fallback:@"QuotaDog"];
    self.statusItem.button.toolTip = tooltip;
    if ([self isPanelVisible]) {
        [self rebuildPanelContent];
        [self demoteApplicationWindowsKeepingPanelFront];
    }
}

- (void)togglePanel:(id)sender {
    if ([self isPanelVisible]) {
        [self closePanel:sender];
        return;
    }

    [self rebuildPanelContent];
    [self positionPanelUnderStatusItem];
    [self.panel orderFrontRegardless];
    [self demoteApplicationWindowsKeepingPanelFront];
    [self startDismissMonitoring];
    if (self.onShow) self.onShow();
}

- (void)positionPanelUnderStatusItem {
    NSStatusBarButton *button = self.statusItem.button;
    NSRect buttonScreenRect = NSZeroRect;
    if (button.window) {
        buttonScreenRect = [button.window convertRectToScreen:[button convertRect:button.bounds toView:nil]];
    }

    NSPoint mouse = [NSEvent mouseLocation];
    NSScreen *mouseScreen = [self screenContainingPoint:mouse];
    NSScreen *buttonScreen = button.window.screen;
    BOOL buttonLooksValid = buttonScreenRect.size.width > 0.5 && buttonScreenRect.size.height > 0.5;
    BOOL sameScreen = !mouseScreen || !buttonScreen || mouseScreen == buttonScreen;

    NSRect anchorScreenRect = buttonScreenRect;
    if (!buttonLooksValid || !sameScreen) {
        CGFloat width = MAX(buttonScreenRect.size.width, 22.0);
        CGFloat height = MAX(buttonScreenRect.size.height, [NSStatusBar systemStatusBar].thickness);
        NSScreen *screen = mouseScreen ?: buttonScreen ?: NSScreen.mainScreen;
        CGFloat menuBarMinY = screen ? NSMaxY(screen.visibleFrame) : mouse.y;
        anchorScreenRect = NSMakeRect(mouse.x - width / 2.0, menuBarMinY, width, height);
    }

    NSSize size = self.panelSize;
    CGFloat x = NSMidX(anchorScreenRect) - size.width / 2.0;
    CGFloat y = NSMinY(anchorScreenRect) - size.height - 6.0;
    NSScreen *screen = [self screenContainingPoint:NSMakePoint(NSMidX(anchorScreenRect), NSMinY(anchorScreenRect))]
        ?: NSScreen.mainScreen;
    if (screen) {
        NSRect visible = screen.visibleFrame;
        x = MIN(MAX(x, NSMinX(visible) + 8.0), NSMaxX(visible) - size.width - 8.0);
        if (y < NSMinY(visible) + 8.0) {
            y = NSMaxY(anchorScreenRect) + 6.0; // flip below → above if needed
        }
    }
    [self.panel setFrame:NSMakeRect(x, y, size.width, size.height) display:YES];
}

- (NSScreen *)screenContainingPoint:(NSPoint)point {
    for (NSScreen *screen in NSScreen.screens) {
        if (NSPointInRect(point, screen.frame)) {
            return screen;
        }
    }
    return nil;
}

- (void)configurePanelAppearance:(BOOL)darkTheme {
    self.panel.appearance = darkTheme
        ? [NSAppearance appearanceNamed:NSAppearanceNameDarkAqua]
        : [NSAppearance appearanceNamed:NSAppearanceNameAqua];
}

- (void)demoteApplicationWindowsKeepingPanelFront {
    for (NSWindow *window in NSApp.windows) {
        if (window == self.panel) continue;
        if (window == self.statusItem.button.window) continue;
        if (!window.isVisible) continue;
        if (window.level >= NSStatusWindowLevel) continue;
        [window orderBack:nil];
    }
    [self.panel orderFrontRegardless];
}

- (void)refreshClicked:(id)sender {
    if (self.onRefresh) self.onRefresh();
}

- (void)providerFilterClicked:(QDPillButton *)sender {
    NSString *provider = sender.providerIdentifier ?: @"";
    NSString *selected = [self stringForKey:@"selectedProvider" fallback:@""];
    if ([provider isEqualToString:selected]) return;

    NSMutableDictionary *updatedState = [self.state mutableCopy];
    updatedState[@"selectedProvider"] = provider;
    self.state = updatedState;
    self.pendingProviderSelection = provider;

    QDPalette palette = QDPaletteForDark(QDResolveDarkTheme(self.state));
    for (NSView *view in sender.superview.subviews) {
        if (![view isKindOfClass:[QDPillButton class]]) continue;
        QDPillButton *button = (QDPillButton *)view;
        BOOL active = [button.providerIdentifier isEqualToString:provider];
        button.fgColor = active ? palette.textPrimary : palette.textSecondary;
        [button setNeedsDisplay:YES];
    }

    self.providerSelectionGeneration += 1;
    NSUInteger generation = self.providerSelectionGeneration;
    [NSAnimationContext runAnimationGroup:^(NSAnimationContext *context) {
        context.duration = 0.18;
        self.providerIndicator.animator.frame = sender.frame;
    } completionHandler:^{
        if (generation != self.providerSelectionGeneration || !self.onSelectProvider) return;
        self.onSelectProvider(provider.UTF8String);
    }];
}

- (void)openHideClicked:(id)sender {
    [self closePanel:sender];
    if (self.onOpenHide) self.onOpenHide();
    // Explicit Open app: activate and raise main windows (tray panel itself avoids this).
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(50 * NSEC_PER_MSEC)),
                   dispatch_get_main_queue(), ^{
        [NSApp activateIgnoringOtherApps:YES];
        for (NSWindow *window in NSApp.windows) {
            if (window == self.panel) continue;
            if (window == self.statusItem.button.window) continue;
            if (!window.isVisible) continue;
            if (window.level >= NSStatusWindowLevel) continue;
            [window makeKeyAndOrderFront:nil];
        }
    });
}

- (void)quitClicked:(id)sender {
    [self closePanel:sender];
    if (self.onQuit) self.onQuit();
}

- (void)closePanel:(id)sender {
    [self.panel orderOut:sender];
    [self stopDismissMonitoring];
}

- (void)startDismissMonitoring {
    if (self.localEventMonitor || self.globalEventMonitor || self.resignActiveObserver ||
        self.becomeActiveObserver || self.windowBecomeKeyObserver) {
        return;
    }

    __weak typeof(self) weakSelf = self;
    NSEventMask mouseMask = NSEventMaskLeftMouseDown | NSEventMaskRightMouseDown | NSEventMaskOtherMouseDown;

    self.localEventMonitor = [NSEvent addLocalMonitorForEventsMatchingMask:mouseMask handler:^NSEvent *(NSEvent *event) {
        QDStatusBarController *strongSelf = weakSelf;
        if (!strongSelf || ![strongSelf isPanelVisible]) {
            return event;
        }
        if ([strongSelf eventIsInsideStatusButton:event] || [strongSelf eventIsInsidePanel:event]) {
            return event;
        }
        [strongSelf closePanel:event];
        return event;
    }];

    self.globalEventMonitor = [NSEvent addGlobalMonitorForEventsMatchingMask:mouseMask handler:^(NSEvent *event) {
        QDStatusBarController *strongSelf = weakSelf;
        if (!strongSelf || ![strongSelf isPanelVisible]) {
            return;
        }
        [strongSelf closePanel:event];
    }];

    self.resignActiveObserver = [[NSNotificationCenter defaultCenter]
        addObserverForName:NSApplicationWillResignActiveNotification
                    object:NSApp
                     queue:[NSOperationQueue mainQueue]
                usingBlock:^(__unused NSNotification *notification) {
                    QDStatusBarController *strongSelf = weakSelf;
                    if ([strongSelf isPanelVisible]) {
                        [strongSelf closePanel:nil];
                    }
                }];

    self.becomeActiveObserver = [[NSNotificationCenter defaultCenter]
        addObserverForName:NSApplicationDidBecomeActiveNotification
                    object:NSApp
                     queue:[NSOperationQueue mainQueue]
                usingBlock:^(__unused NSNotification *notification) {
                    QDStatusBarController *strongSelf = weakSelf;
                    if ([strongSelf isPanelVisible]) {
                        [strongSelf demoteApplicationWindowsKeepingPanelFront];
                    }
                }];

    self.windowBecomeKeyObserver = [[NSNotificationCenter defaultCenter]
        addObserverForName:NSWindowDidBecomeKeyNotification
                    object:nil
                     queue:[NSOperationQueue mainQueue]
                usingBlock:^(NSNotification *notification) {
                    QDStatusBarController *strongSelf = weakSelf;
                    if (!strongSelf || ![strongSelf isPanelVisible]) return;
                    NSWindow *keyWindow = notification.object;
                    if (!keyWindow || keyWindow == strongSelf.panel) {
                        return;
                    }
                    if (keyWindow == strongSelf.statusItem.button.window) return;
                    if (keyWindow.level >= NSStatusWindowLevel) return;
                    [keyWindow orderBack:nil];
                    [strongSelf.panel orderFrontRegardless];
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
    if (self.becomeActiveObserver) {
        [[NSNotificationCenter defaultCenter] removeObserver:self.becomeActiveObserver];
        self.becomeActiveObserver = nil;
    }
    if (self.windowBecomeKeyObserver) {
        [[NSNotificationCenter defaultCenter] removeObserver:self.windowBecomeKeyObserver];
        self.windowBecomeKeyObserver = nil;
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

- (BOOL)eventIsInsidePanel:(NSEvent *)event {
    return event.window == self.panel;
}

#pragma mark - Layout helpers

- (NSTextField *)label:(NSString *)text
              fontSize:(CGFloat)fontSize
                weight:(NSFontWeight)weight
                 color:(NSColor *)color {
    NSTextField *label = [NSTextField labelWithString:text ?: @""];
    label.font = [NSFont systemFontOfSize:fontSize weight:weight];
    label.textColor = color;
    label.lineBreakMode = NSLineBreakByTruncatingTail;
    label.usesSingleLineMode = YES;
    label.drawsBackground = NO;
    label.bordered = NO;
    label.editable = NO;
    return label;
}

- (CGFloat)heightForAccount:(NSDictionary *)account {
    NSArray *windows = [account[@"windows"] isKindOfClass:[NSArray class]] ? account[@"windows"] : @[];
    CGFloat height = QDCardPad + QDAvatarSize + 12.0; // header + gap
    if (windows.count == 0) {
        height += 32.0; // empty muted box
    } else {
        NSUInteger count = MIN(windows.count, QDMaxWindows);
        // each window: label(14) + gap4 + bar6 + gap4 + footer14 = 42, plus gap 8 between
        height += count * 42.0 + MAX(0, (NSInteger)count - 1) * 8.0;
    }
    height += QDCardPad;
    return height;
}

- (NSView *)buildProviderAvatar:(NSString *)provider palette:(QDPalette)palette {
    // Match QdProviderAvatar: transparent slot, tinted vector logo scaled inside.
    QDFlippedView *slot = [[QDFlippedView alloc] initWithFrame:NSMakeRect(0, 0, QDAvatarSize, QDAvatarSize)];
    CGFloat iconSize = QDAvatarSize * QDProviderIconScale(provider);
    NSImage *logo = QDProviderLogoImage(provider, QDProviderAccent(provider, palette), iconSize);
    if (logo) {
        NSImageView *imageView = [[NSImageView alloc] initWithFrame:NSMakeRect(
            (QDAvatarSize - iconSize) / 2.0,
            (QDAvatarSize - iconSize) / 2.0,
            iconSize,
            iconSize)];
        imageView.image = logo;
        imageView.imageScaling = NSImageScaleProportionallyUpOrDown;
        imageView.animates = NO;
        imageView.editable = NO;
        [slot addSubview:imageView];
    }
    return slot;
}

- (NSView *)buildProviderSwitcher:(NSArray *)filters
                         selected:(NSString *)selected
                            width:(CGFloat)width
                          palette:(QDPalette)palette {
    CGFloat height = 36.0;
    CGFloat inset = 4.0;
    CGFloat segmentHeight = height - inset * 2.0;
    QDFillView *track = [[QDFillView alloc] initWithFrame:NSMakeRect(0, 0, width, height)];
    track.fillColor = palette.surfaceMuted;
    track.cornerRadius = height / 2.0;

    NSFont *font = [NSFont systemFontOfSize:11.0 weight:NSFontWeightMedium];
    CGFloat availableWidth = width - inset * 2.0;
    NSMutableArray<NSNumber *> *preferredWidths = [NSMutableArray arrayWithCapacity:filters.count];
    CGFloat preferredTotal = 0;
    for (id value in filters) {
        NSDictionary *filter = [value isKindOfClass:[NSDictionary class]] ? value : @{};
        NSString *label = [self stringIn:filter key:@"label" fallback:@"All"];
        CGFloat textWidth = ceil([label sizeWithAttributes:@{NSFontAttributeName: font}].width);
        CGFloat segmentWidth = textWidth + 20.0;
        [preferredWidths addObject:@(segmentWidth)];
        preferredTotal += segmentWidth;
    }
    CGFloat widthScale = preferredTotal > availableWidth ? availableWidth / preferredTotal : 1.0;

    NSMutableArray<NSValue *> *frames = [NSMutableArray arrayWithCapacity:filters.count];
    CGFloat x = inset;
    NSUInteger selectedIndex = NSNotFound;
    for (NSUInteger index = 0; index < filters.count; index++) {
        NSDictionary *filter = [filters[index] isKindOfClass:[NSDictionary class]] ? filters[index] : @{};
        NSString *identifier = [self stringIn:filter key:@"id" fallback:@""];
        if ([identifier isEqualToString:selected]) selectedIndex = index;
        CGFloat segmentWidth = index + 1 == filters.count
            ? NSMaxX(track.bounds) - inset - x
            : preferredWidths[index].doubleValue * widthScale;
        NSRect frame = NSMakeRect(x, inset, segmentWidth, segmentHeight);
        [frames addObject:[NSValue valueWithRect:frame]];
        x += segmentWidth;
    }
    if (selectedIndex == NSNotFound) selectedIndex = 0;

    NSRect selectedFrame = filters.count > 0 ? frames[selectedIndex].rectValue : NSZeroRect;
    QDFillView *indicator = [[QDFillView alloc] initWithFrame:selectedFrame];
    indicator.fillColor = palette.surface;
    indicator.cornerRadius = segmentHeight / 2.0;
    [track addSubview:indicator];
    self.providerIndicator = indicator;

    for (NSUInteger index = 0; index < filters.count; index++) {
        NSDictionary *filter = [filters[index] isKindOfClass:[NSDictionary class]] ? filters[index] : @{};
        NSString *identifier = [self stringIn:filter key:@"id" fallback:@""];
        NSString *label = [self stringIn:filter key:@"label" fallback:@"All"];
        BOOL active = index == selectedIndex;
        QDPillButton *button = [QDPillButton buttonWithTitle:label
                                                       style:QDButtonStyleSecondary
                                                     palette:palette
                                                      target:self
                                                      action:@selector(providerFilterClicked:)];
        button.providerIdentifier = identifier;
        button.titleFont = font;
        button.restBg = NSColor.clearColor;
        button.hoverBg = palette.surfaceHover;
        button.pressedBg = palette.surfaceMuted;
        button.fgColor = active ? palette.textPrimary : palette.textSecondary;
        button.frame = frames[index].rectValue;
        button.toolTip = label;
        [track addSubview:button];
    }

    return track;
}

- (NSView *)buildUsageWindowRow:(NSDictionary *)window
                          width:(CGFloat)width
                        palette:(QDPalette)palette {
    NSInteger usedPct = [self integerIn:window key:@"usedPct" fallback:0];
    NSInteger remainingPct = [self integerIn:window key:@"remainingPct" fallback:0];
    NSString *resetLabel = [self stringIn:window key:@"resetLabel" fallback:@"—"];
    NSColor *fill = QDUsageFill(usedPct, palette);

    QDFlippedView *row = [[QDFlippedView alloc] initWithFrame:NSMakeRect(0, 0, width, 42)];
    row.wantsLayer = YES;

    NSTextField *name = [self label:[self stringIn:window key:@"label" fallback:@"Usage"]
                           fontSize:11.0
                             weight:NSFontWeightSemibold
                              color:palette.textPrimary];
    name.frame = NSMakeRect(0, 0, width - 80, 14);
    [row addSubview:name];

    NSTextField *used = [self label:[NSString stringWithFormat:@"%ld%% used", (long)usedPct]
                           fontSize:11.0
                             weight:NSFontWeightRegular
                              color:fill];
    used.alignment = NSTextAlignmentRight;
    used.frame = NSMakeRect(width - 80, 0, 80, 14);
    [row addSubview:used];

    QDProgressBar *bar = [[QDProgressBar alloc] initWithFrame:NSMakeRect(0, 18, width, QDProgressHeight)];
    bar.trackColor = palette.surfaceMuted;
    bar.fillColor = fill;
    bar.progress = usedPct / 100.0;
    [row addSubview:bar];

    NSTextField *left = [self label:[NSString stringWithFormat:@"%ld%% left", (long)remainingPct]
                           fontSize:11.0
                             weight:NSFontWeightRegular
                              color:palette.textTertiary];
    left.frame = NSMakeRect(0, 28, width * 0.45, 14);
    [row addSubview:left];

    NSTextField *reset = [self label:[NSString stringWithFormat:@"resets %@", resetLabel]
                            fontSize:11.0
                              weight:NSFontWeightRegular
                               color:palette.textTertiary];
    reset.alignment = NSTextAlignmentRight;
    reset.frame = NSMakeRect(width * 0.45, 28, width * 0.55, 14);
    [row addSubview:reset];

    return row;
}

- (NSView *)buildAccountCard:(NSDictionary *)account
                       width:(CGFloat)width
                     palette:(QDPalette)palette {
    CGFloat height = [self heightForAccount:account];
    QDFillView *card = [[QDFillView alloc] initWithFrame:NSMakeRect(0, 0, width, height)];
    card.fillColor = palette.surface;
    card.borderColor = palette.border;
    card.cornerRadius = QDCardRadius;

    CGFloat contentWidth = width - QDCardPad * 2.0;
    CGFloat y = QDCardPad;

    NSString *provider = [self stringIn:account key:@"provider" fallback:@"CODEX"];
    NSView *avatar = [self buildProviderAvatar:provider palette:palette];
    avatar.frame = NSMakeRect(QDCardPad, y, QDAvatarSize, QDAvatarSize);
    [card addSubview:avatar];

    CGFloat titleX = QDCardPad + QDAvatarSize + 8.0;
    CGFloat titleW = contentWidth - QDAvatarSize - 8.0;

    NSTextField *title = [self label:[self stringIn:account key:@"title" fallback:@"Account"]
                            fontSize:14.0
                              weight:NSFontWeightSemibold
                               color:palette.textPrimary];
    title.frame = NSMakeRect(titleX, y, titleW, 16);
    [card addSubview:title];

    BOOL busy = [self boolIn:account key:@"busy"];
    NSTextField *status = [self label:[self stringIn:account key:@"status" fallback:@"No usage data yet"]
                             fontSize:11.0
                               weight:NSFontWeightRegular
                                color:busy ? palette.primary : palette.textTertiary];
    status.frame = NSMakeRect(titleX, y + 16, titleW, 14);
    [card addSubview:status];

    y += QDAvatarSize + 12.0;

    NSArray *windows = [account[@"windows"] isKindOfClass:[NSArray class]] ? account[@"windows"] : @[];
    if (windows.count == 0) {
        QDFillView *empty = [[QDFillView alloc] initWithFrame:NSMakeRect(QDCardPad, y, contentWidth, 32)];
        empty.fillColor = palette.surfaceMuted;
        empty.cornerRadius = 10.0;
        NSTextField *emptyLabel = [self label:[self stringIn:account key:@"status" fallback:@"No usage data yet"]
                                     fontSize:11.0
                                       weight:NSFontWeightRegular
                                        color:palette.textSecondary];
        emptyLabel.frame = NSMakeRect(12, 8, contentWidth - 24, 16);
        [empty addSubview:emptyLabel];
        [card addSubview:empty];
    } else {
        NSUInteger count = MIN(windows.count, QDMaxWindows);
        for (NSUInteger i = 0; i < count; i++) {
            NSDictionary *window = [windows[i] isKindOfClass:[NSDictionary class]] ? windows[i] : @{};
            NSView *row = [self buildUsageWindowRow:window width:contentWidth palette:palette];
            row.frame = NSMakeRect(QDCardPad, y, contentWidth, 42);
            [card addSubview:row];
            y += 42.0 + (i + 1 < count ? 8.0 : 0.0);
        }
    }

    return card;
}

- (CGFloat)heightForAccountsContent:(NSArray *)accounts moreAccounts:(NSInteger)moreAccounts {
    if (accounts.count == 0) {
        return QDEmptyContentHeight;
    }
    NSUInteger count = MIN(accounts.count, QDMaxAccounts);
    CGFloat totalHeight = 0;
    for (NSUInteger i = 0; i < count; i++) {
        NSDictionary *account = [accounts[i] isKindOfClass:[NSDictionary class]] ? accounts[i] : @{};
        totalHeight += [self heightForAccount:account];
        if (i + 1 < count) totalHeight += QDAccountGap;
    }
    if (moreAccounts > 0) {
        totalHeight += QDAccountGap + 16.0;
    }
    return totalHeight;
}

- (void)rebuildPanelContent {
    BOOL darkTheme = QDResolveDarkTheme(self.state);
    QDPalette palette = QDPaletteForDark(darkTheme);

    CGFloat contentWidth = QDPanelWidth - QDOuterPad * 2.0;
    NSArray *accounts = [self arrayForKey:@"accounts"];
    NSArray *providerFilters = [self arrayForKey:@"providerFilters"];
    BOOL showsProviderSwitcher = providerFilters.count > 1;
    NSInteger moreAccounts = [self integerForKey:@"moreAccounts" fallback:0];
    CGFloat intrinsicContentHeight = [self heightForAccountsContent:accounts moreAccounts:moreAccounts];

    // Header + single-row footer chrome; leftover budget is the scrollable content max.
    CGFloat headerHeight = QDOuterPad + 44.0 + QDSectionGap;
    if (showsProviderSwitcher) {
        headerHeight += 36.0 + QDSectionGap;
    }
    CGFloat footerHeight = QDSectionGap + QDButtonHeight + QDFooterBottomPad;
    CGFloat maxContentHeight = MAX(80.0, QDPanelMaxHeight - headerHeight - footerHeight);
    CGFloat contentHeight = MIN(intrinsicContentHeight, maxContentHeight);
    CGFloat panelHeight = headerHeight + contentHeight + footerHeight;

    QDFillView *root = [[QDFillView alloc] initWithFrame:NSMakeRect(0, 0, QDPanelWidth, panelHeight)];
#if QD_DEBUG_LAYOUT
    root.fillColor = QDHex(0xC026C0);
#else
    root.fillColor = palette.backgroundElevated;
#endif
    root.cornerRadius = 14.0;
    root.autoresizingMask = NSViewNotSizable;

#if QD_DEBUG_LAYOUT
    QDFillView *dbgStrip = [[QDFillView alloc] initWithFrame:NSMakeRect(0, headerHeight - 18, QDPanelWidth, 18)];
    dbgStrip.fillColor = QDHex(0xEF4444);
    [root addSubview:dbgStrip];
    NSTextField *dbgLabel = [self label:[NSString stringWithFormat:@"DBG %@ — if missing, old dylib still loaded", QDStatusBarBuildId]
                               fontSize:10.0
                                 weight:NSFontWeightBold
                                  color:QDHex(0xFFFFFF)];
    dbgLabel.frame = NSMakeRect(8, headerHeight - 17, QDPanelWidth - 16, 16);
    [root addSubview:dbgLabel];
#endif

    // Header
    CGFloat headerButtonSize = 28.0;
    CGFloat titleWidth = contentWidth - headerButtonSize - 12.0;
#if QD_DEBUG_LAYOUT
    QDFillView *headerBg = [[QDFillView alloc] initWithFrame:NSMakeRect(0, 0, QDPanelWidth, headerHeight - 18)];
    headerBg.fillColor = QDHex(0x2563EB);
    [root addSubview:headerBg];
#endif

    NSString *titleText = @"QuotaDog";
#if QD_DEBUG_LAYOUT
    titleText = [NSString stringWithFormat:@"QuotaDog [%@]", QDStatusBarBuildId];
#endif
    NSTextField *title = [self label:titleText
                            fontSize:20.0
                              weight:NSFontWeightBold
                               color:palette.textPrimary];
    title.frame = NSMakeRect(QDOuterPad, QDOuterPad, titleWidth, 26);
    [root addSubview:title];

    NSTextField *summary = [self label:[self stringForKey:@"summary" fallback:@"No accounts yet"]
                              fontSize:12.0
                                weight:NSFontWeightRegular
                                 color:palette.textSecondary];
    summary.frame = NSMakeRect(QDOuterPad, QDOuterPad + 28, titleWidth, 16);
    [root addSubview:summary];

    QDPillButton *refresh = [QDPillButton iconButtonWithSystemSymbol:@"arrow.clockwise"
                                                             palette:palette
                                                              target:self
                                                              action:@selector(refreshClicked:)];
    refresh.frame = NSMakeRect(QDPanelWidth - QDOuterPad - headerButtonSize, QDOuterPad + 4.0, headerButtonSize, headerButtonSize);
    refresh.enabled = [self boolForKey:@"refreshEnabled"];
    refresh.toolTip = @"Refresh";
    [root addSubview:refresh];

    if (showsProviderSwitcher) {
        NSView *switcher = [self buildProviderSwitcher:providerFilters
                                              selected:[self stringForKey:@"selectedProvider" fallback:@""]
                                                 width:contentWidth
                                               palette:palette];
        switcher.frame = NSMakeRect(
            QDOuterPad,
            QDOuterPad + 44.0 + QDSectionGap,
            contentWidth,
            36.0);
        [root addSubview:switcher];
    } else {
        self.providerIndicator = nil;
    }

    CGFloat contentTop = headerHeight;

    if (accounts.count == 0) {
        QDFlippedView *empty = [[QDFlippedView alloc] initWithFrame:NSMakeRect(QDOuterPad, contentTop, contentWidth, contentHeight)];
#if QD_DEBUG_LAYOUT
        empty.wantsLayer = YES;
        empty.layer.backgroundColor = QDHex(0xEAB308).CGColor;
#endif
        NSTextField *emptyTitle = [self label:@"No usage data yet"
                                     fontSize:16.0
                                       weight:NSFontWeightSemibold
                                        color:palette.textPrimary];
        emptyTitle.alignment = NSTextAlignmentCenter;
        emptyTitle.frame = NSMakeRect(0, contentHeight / 2.0 - 28, contentWidth, 22);
        [empty addSubview:emptyTitle];

        NSTextField *emptyBody = [self label:@"Add an account in QuotaDog to show live usage here."
                                    fontSize:12.0
                                      weight:NSFontWeightRegular
                                       color:palette.textSecondary];
        emptyBody.alignment = NSTextAlignmentCenter;
        emptyBody.usesSingleLineMode = NO;
        emptyBody.maximumNumberOfLines = 2;
        emptyBody.frame = NSMakeRect(20, contentHeight / 2.0 - 4, contentWidth - 40, 36);
        [empty addSubview:emptyBody];
        [root addSubview:empty];
    } else {
        NSUInteger count = MIN(accounts.count, QDMaxAccounts);
        QDFlippedView *document = [[QDFlippedView alloc] initWithFrame:NSMakeRect(0, 0, contentWidth, intrinsicContentHeight)];
        document.wantsLayer = YES;
#if QD_DEBUG_LAYOUT
        document.layer.backgroundColor = QDHex(0xCA8A04).CGColor;
#endif

        CGFloat cursor = 0;
        for (NSUInteger i = 0; i < count; i++) {
            NSDictionary *account = [accounts[i] isKindOfClass:[NSDictionary class]] ? accounts[i] : @{};
            CGFloat h = [self heightForAccount:account];
            NSView *card = [self buildAccountCard:account width:contentWidth palette:palette];
            card.frame = NSMakeRect(0, cursor, contentWidth, h);
            [document addSubview:card];
            cursor += h + (i + 1 < count ? QDAccountGap : 0.0);
        }
        if (moreAccounts > 0) {
            cursor += QDAccountGap;
            NSString *moreText = moreAccounts == 1
                ? @"+1 more account in QuotaDog"
                : [NSString stringWithFormat:@"+%ld more accounts in QuotaDog", (long)moreAccounts];
            NSTextField *more = [self label:moreText
                                   fontSize:11.0
                                     weight:NSFontWeightRegular
                                      color:palette.textTertiary];
            more.frame = NSMakeRect(8, cursor, contentWidth - 16, 16);
            [document addSubview:more];
            cursor += 16.0;
        }

        CGFloat builtHeight = MAX(cursor, 1.0);
        document.frame = NSMakeRect(0, 0, contentWidth, builtHeight);
        contentHeight = MIN(builtHeight, maxContentHeight);
        panelHeight = headerHeight + contentHeight + footerHeight;
        root.frame = NSMakeRect(0, 0, QDPanelWidth, panelHeight);

        if (builtHeight > contentHeight + 0.5) {
            NSScrollView *scroll = [[NSScrollView alloc] initWithFrame:NSMakeRect(QDOuterPad, contentTop, contentWidth, contentHeight)];
#if QD_DEBUG_LAYOUT
            scroll.drawsBackground = YES;
            scroll.backgroundColor = QDHex(0xEAB308);
#else
            scroll.drawsBackground = NO;
#endif
            scroll.hasVerticalScroller = YES;
            scroll.hasHorizontalScroller = NO;
            scroll.autohidesScrollers = YES;
            scroll.borderType = NSNoBorder;
            scroll.documentView = document;
            [document scrollPoint:NSMakePoint(0, 0)];
            [root addSubview:scroll];
        } else {
            document.frame = NSMakeRect(QDOuterPad, contentTop, contentWidth, builtHeight);
#if QD_DEBUG_LAYOUT
            QDFillView *contentBg = [[QDFillView alloc] initWithFrame:document.frame];
            contentBg.fillColor = QDHex(0xEAB308);
            [root addSubview:contentBg];
#endif
            [root addSubview:document];
        }
    }

    // Footer: quiet text actions — don't compete with account cards.
    CGFloat footerTop = contentTop + contentHeight + QDSectionGap;
    NSString *openHideTitle = @"Open app";
    QDPillButton *openHide = [QDPillButton buttonWithTitle:openHideTitle
                                                     style:QDButtonStyleSecondary
                                                   palette:palette
                                                    target:self
                                                    action:@selector(openHideClicked:)];
    openHide.fgColor = palette.primary;
    openHide.frame = NSMakeRect(QDOuterPad, footerTop, 72.0, QDButtonHeight);
    [root addSubview:openHide];

    QDPillButton *quit = [QDPillButton buttonWithTitle:@"Quit"
                                                 style:QDButtonStyleGhost
                                               palette:palette
                                                target:self
                                                action:@selector(quitClicked:)];
    quit.frame = NSMakeRect(QDPanelWidth - QDOuterPad - 56.0, footerTop, 56.0, QDButtonHeight);
    [root addSubview:quit];

#if QD_DEBUG_LAYOUT
    CGFloat footerBlockHeight = NSMaxY(quit.frame) - (footerTop - QDSectionGap) + QDFooterBottomPad;
    QDFillView *footerBg = [[QDFillView alloc] initWithFrame:NSMakeRect(
        0, footerTop - QDSectionGap, QDPanelWidth, footerBlockHeight)];
    footerBg.fillColor = QDHex(0x06B6D4);
    [root addSubview:footerBg];
    [root addSubview:openHide];
    [root addSubview:quit];
    QDLog(@"footerTop=%.1f footerBlockH=%.1f open=%@ quit=%@ contentH=%.1f",
          footerTop, footerBlockHeight,
          NSStringFromRect(openHide.frame), NSStringFromRect(quit.frame),
          contentHeight);
#endif

    panelHeight = NSMaxY(quit.frame) + QDFooterBottomPad;
    NSSize size = NSMakeSize(QDPanelWidth, panelHeight);
    root.frame = NSMakeRect(0, 0, size.width, size.height);
#if QD_DEBUG_LAYOUT
    root.fillColor = QDHex(0xC026C0);
#endif
    root.appearance = darkTheme
        ? [NSAppearance appearanceNamed:NSAppearanceNameDarkAqua]
        : [NSAppearance appearanceNamed:NSAppearanceNameAqua];

    self.panelSize = size;
    self.panel.contentView = root;
    [self configurePanelAppearance:darkTheme];

    if ([self isPanelVisible]) {
        NSRect frame = self.panel.frame;
        CGFloat top = NSMaxY(frame);
        frame.size = size;
        frame.origin.y = top - size.height;
        [self.panel setFrame:frame display:YES];
    } else {
        [self.panel setContentSize:size];
    }

#if QD_DEBUG_LAYOUT
    QDLog(@"rebuild build=%@ accounts=%lu contentH=%.1f size=%.0fx%.0f panelFrame=%@ contentView=%@ dark=%d",
          QDStatusBarBuildId,
          (unsigned long)accounts.count,
          contentHeight,
          size.width, size.height,
          NSStringFromRect(self.panel.frame),
          NSStringFromRect(root.frame),
          darkTheme);
#endif
}

#pragma mark - JSON helpers

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
    return [self boolIn:self.state key:key];
}

- (BOOL)boolIn:(NSDictionary *)dictionary key:(NSString *)key {
    id value = dictionary[key];
    return [value respondsToSelector:@selector(boolValue)] ? [value boolValue] : NO;
}

@end

#pragma mark - C API

void *qd_statusbar_create(QDActionCallback onRefresh,
                          QDActionCallback onShow,
                          QDActionCallback onOpenHide,
                          QDActionCallback onQuit,
                          QDProviderCallback onSelectProvider) {
    __block QDStatusBarController *controller = nil;
    void (^create)(void) = ^{
        controller = [[QDStatusBarController alloc] initWithRefresh:onRefresh
                                                               show:onShow
                                                           openHide:onOpenHide
                                                               quit:onQuit
                                                     selectProvider:onSelectProvider];
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
    void (^dispose)(void) = ^{
        controller.providerSelectionGeneration += 1;
        controller.onRefresh = NULL;
        controller.onShow = NULL;
        controller.onOpenHide = NULL;
        controller.onQuit = NULL;
        controller.onSelectProvider = NULL;
        controller.pendingProviderSelection = nil;
        [controller closePanel:nil];
        [[NSStatusBar systemStatusBar] removeStatusItem:controller.statusItem];
        controller.statusItem = nil;
    };
    if ([NSThread isMainThread]) {
        dispose();
    } else {
        dispatch_sync(dispatch_get_main_queue(), dispose);
    }
}
