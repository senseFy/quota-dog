#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
  printf 'SKIP: macOS status bar layout requires AppKit.\n'
  exit 0
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/quotadog-status-bar-test.XXXXXX")"
trap 'rm -rf "$TEMP_DIR"' EXIT

cat > "$TEMP_DIR/test.m" <<'OBJC'
#import "QuotaDogStatusBar.m"
#include <assert.h>

int main(void) {
    @autoreleasepool {
        [NSApplication sharedApplication];
        // Avoid installing a status item; only exercise the real card layout.
        QDStatusBarController *controller = [[QDStatusBarController alloc] init];
        NSArray<NSString *> *plans = @[
            @"",
            @"Pro",
            @"SuperGrok Heavy",
            @"Google AI Ultra for Business and Enterprise",
            @"企业高级订阅套餐年度版本支持团队协作和高级人工智能功能",
            @"خطة الاشتراك السنوية المتقدمة للمؤسسات والفرق",
        ];
        for (NSNumber *width in @[@380, @220, @128]) {
            for (NSNumber *refreshable in @[@YES, @NO]) {
                for (NSString *plan in plans) {
                    NSDictionary *account = @{
                        @"title": @"sample@example.test",
                        @"planLabel": plan,
                        @"provider": @"ANTIGRAVITY",
                        @"refreshable": refreshable,
                        @"windows": @[],
                    };
                    NSView *card = [controller buildAccountCard:account
                                                        width:width.doubleValue
                                                      palette:QDLightPalette()
                                             remainingDisplay:NO];
                    CGFloat rowRight = width.doubleValue - QDCardPad;
                    if (refreshable.boolValue) {
                        rowRight -= QDAccountRefreshSize + QDAccountRefreshGap;
                    }
                    NSUInteger badges = 0;
                    for (NSView *view in card.subviews) {
                        if (![view isKindOfClass:[QDFillView class]]) continue;
                        for (NSView *child in view.subviews) {
                            if (![child isKindOfClass:[NSTextField class]]) continue;
                            NSTextField *label = (NSTextField *)child;
                            if (![label.stringValue isEqualToString:plan]) continue;
                            badges++;
                            assert(NSMaxX(view.frame) <= rowRight);
                            assert(NSWidth(view.frame) <= 148.0);
                            assert(NSWidth(label.frame) > 0.0);
                            assert(NSMaxX(label.frame) <= NSWidth(view.frame));
                            assert(label.lineBreakMode == NSLineBreakByTruncatingTail);
                            assert([label.toolTip isEqualToString:plan]);
                            if (width.intValue == 380 && [plan isEqualToString:@"Pro"]) {
                                assert(NSWidth(label.frame) >= ceil(label.fittingSize.width));
                            }
                        }
                    }
                    if (plan.length == 0) assert(badges == 0);
                    if (plan.length > 0 && width.intValue >= 220) assert(badges == 1);
                }
            }
        }
    }
    puts("PASS: macOS plan badges preserve refresh space and truncate long labels.");
    return 0;
}
OBJC

clang -fobjc-arc -framework AppKit -framework Foundation \
  -I "$ROOT_DIR/composeApp/src/desktopMain/native/macos" \
  "$TEMP_DIR/test.m" -o "$TEMP_DIR/test"
"$TEMP_DIR/test"
