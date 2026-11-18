#import <Cocoa/Cocoa.h>
#import <WebKit/WebKit.h>
#import <Security/Security.h>

typedef void (*LindoBridgeReply)(long long request, const char *json);
static LindoBridgeReply gEventReply = NULL;

static void sendReply(LindoBridgeReply callback, long long request, id value, NSString *error) {
    if (!callback) return;
    NSMutableDictionary *payload = [NSMutableDictionary new];
    if (error) {
        payload[@"error"] = error;
    } else if (value && value != NSNull.null) {
        if (![NSJSONSerialization isValidJSONObject:@{@"value": value}]) {
            payload[@"error"] = @"Unsupported WebKit result type";
        } else {
            payload[@"value"] = value;
        }
    }
    NSData *data = [NSJSONSerialization dataWithJSONObject:payload options:0 error:nil];
    NSString *text = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] ?: @"{\"error\":\"Invalid response\"}";
    callback(request, text.UTF8String);
}

static BOOL isForumHost(NSString *host) {
    NSString *h = host.lowercaseString;
    return [h isEqualToString:@"linux.do"] || [h hasSuffix:@".linux.do"];
}

static void appendDerLength(NSMutableData *out, NSUInteger len) {
    if (len < 128) {
        uint8_t b = (uint8_t)len;
        [out appendBytes:&b length:1];
    } else if (len <= 0xFF) {
        uint8_t b[2] = {0x81, (uint8_t)len};
        [out appendBytes:b length:2];
    } else {
        uint8_t b[3] = {0x82, (uint8_t)((len >> 8) & 0xFF), (uint8_t)(len & 0xFF)};
        [out appendBytes:b length:3];
    }
}

static NSData *wrapPkcs1PublicKeyInSpki(NSData *pkcs1) {
    // AlgorithmIdentifier for rsaEncryption: 30 0d 06 09 2a 86 48 86 f7 0d 01 01 01 05 00
    static const uint8_t algId[] = {
        0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86,
        0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
    };
    NSMutableData *bitString = [NSMutableData data];
    uint8_t bitTag = 0x03;
    [bitString appendBytes:&bitTag length:1];
    appendDerLength(bitString, pkcs1.length + 1);
    uint8_t unusedBits = 0x00;
    [bitString appendBytes:&unusedBits length:1];
    [bitString appendData:pkcs1];

    NSMutableData *body = [NSMutableData dataWithBytes:algId length:sizeof(algId)];
    [body appendData:bitString];

    NSMutableData *spki = [NSMutableData data];
    uint8_t seqTag = 0x30;
    [spki appendBytes:&seqTag length:1];
    appendDerLength(spki, body.length);
    [spki appendData:body];
    return spki;
}

@interface LindoBridgePanel : NSPanel
@end
@implementation LindoBridgePanel
- (BOOL)canBecomeKeyWindow { return self.isVisible; }
- (void)cancelOperation:(id)sender {
    if ([self.delegate respondsToSelector:@selector(windowShouldClose:)]) {
        [self.delegate windowShouldClose:self];
    }
}
- (BOOL)performKeyEquivalent:(NSEvent *)event {
    NSEventModifierFlags flags = event.modifierFlags & NSEventModifierFlagDeviceIndependentFlagsMask;
    NSString *key = event.charactersIgnoringModifiers.lowercaseString;
    if (flags == NSEventModifierFlagCommand) {
        if ([key isEqualToString:@"w"]) {
            if ([self.delegate respondsToSelector:@selector(windowShouldClose:)]) {
                [self.delegate windowShouldClose:self];
            }
            return YES;
        }
        if ([key isEqualToString:@"x"]) return [NSApp sendAction:@selector(cut:) to:nil from:self];
        if ([key isEqualToString:@"c"]) return [NSApp sendAction:@selector(copy:) to:nil from:self];
        if ([key isEqualToString:@"v"]) return [NSApp sendAction:@selector(paste:) to:nil from:self];
        if ([key isEqualToString:@"a"]) return [NSApp sendAction:@selector(selectAll:) to:nil from:self];
        if ([key isEqualToString:@"z"]) return [NSApp sendAction:NSSelectorFromString(@"undo:") to:nil from:self];
    } else if (flags == (NSEventModifierFlagCommand | NSEventModifierFlagShift)) {
        if ([key isEqualToString:@"z"]) return [NSApp sendAction:NSSelectorFromString(@"redo:") to:nil from:self];
    }
    return [super performKeyEquivalent:event];
}
@end

@interface LindoVerifyMaskView : NSView
@end
@implementation LindoVerifyMaskView
- (BOOL)acceptsFirstMouse:(NSEvent *)event { return YES; }
- (void)mouseDown:(NSEvent *)event {}
- (void)mouseUp:(NSEvent *)event {}
- (void)rightMouseDown:(NSEvent *)event {}
- (void)scrollWheel:(NSEvent *)event {}
@end

@interface LindoBridgeHost : NSObject <WKNavigationDelegate, WKUIDelegate, NSWindowDelegate>
@property WKWebView *web;
@property NSPanel *panel;
@property NSView *panelRoot;
@property LindoVerifyMaskView *maskView;
@property NSProgressIndicator *maskSpinner;
@property NSTextField *maskLabel;
@property BOOL maskVisibleState;
@property BOOL maskDarkState;
@property NSString *maskMessageState;
@property NSProgressIndicator *progress;
@property NSTextField *originLabel;
@property NSButton *browserAuthBtn;
@property BOOL cancelled;
@property BOOL processTerminated;
@property NSString *navigationError;
@property id<NSObject> activityToken;
@end

@implementation LindoBridgeHost
- (instancetype)init {
    self = [super init];
    if (self) {
        _activityToken = [[NSProcessInfo processInfo]
            beginActivityWithOptions:NSActivityUserInitiatedAllowingIdleSystemSleep
                              reason:@"Lindo active network bridge"];
        WKWebsiteDataStore *store = WKWebsiteDataStore.defaultDataStore;
        WKWebViewConfiguration *config = [WKWebViewConfiguration new];
        config.websiteDataStore = store;
        // Keep `_web` 100% unparented (offscreen, no NSWindow/CGSWindow) until `show:loginMode:` actually displays a sheet!
        // This guarantees background `/challenge` prewarms & `fetch()` calls NEVER touch WindowServer, NSCursor, or screenshot drags.
        _web = [[WKWebView alloc] initWithFrame:NSMakeRect(0, 0, 680, 524) configuration:config];
        _web.navigationDelegate = self;
        _web.UIDelegate = self;
        _web.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
        _maskVisibleState = NO;
        _maskDarkState = YES;
        _maskMessageState = @"正在连接安全验证服务…";
    }
    return self;
}

- (void)ensurePanelCreated {
    if (_panel) return;
    _panel = [[LindoBridgePanel alloc]
        initWithContentRect:NSMakeRect(0, 0, 680, 560)
                  styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable | NSWindowStyleMaskResizable
                    backing:NSBackingStoreBuffered
                      defer:YES];
    _panel.title = @"linux.do";
    _panel.releasedWhenClosed = NO;
    _panel.delegate = self;
    _panel.hidesOnDeactivate = NO;
    _panel.minSize = NSMakeSize(380, 400);

    NSView *root = [[NSView alloc] initWithFrame:NSMakeRect(0, 0, 680, 560)];
    root.wantsLayer = YES;
    root.layer.backgroundColor = [NSColor colorWithRed:0.063 green:0.071 blue:0.090 alpha:1.0].CGColor;
    _panelRoot = root;

    _maskView = [[LindoVerifyMaskView alloc] initWithFrame:NSMakeRect(0, 0, 680, 524)];
    _maskView.wantsLayer = YES;
    _maskView.layer.backgroundColor = [NSColor colorWithRed:0.063 green:0.071 blue:0.090 alpha:1.0].CGColor;
    _maskView.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    _maskView.hidden = YES;

    _maskSpinner = [[NSProgressIndicator alloc] initWithFrame:NSMakeRect(328, 276, 24, 24)];
    _maskSpinner.style = NSProgressIndicatorStyleSpinning;
    _maskSpinner.controlSize = NSControlSizeRegular;
    _maskSpinner.displayedWhenStopped = NO;
    _maskSpinner.autoresizingMask = NSViewMinXMargin | NSViewMaxXMargin | NSViewMinYMargin | NSViewMaxYMargin;
    [_maskView addSubview:_maskSpinner];

    _maskLabel = [NSTextField labelWithString:@"正在连接安全验证服务…"];
    _maskLabel.textColor = NSColor.secondaryLabelColor;
    _maskLabel.font = [NSFont systemFontOfSize:13 weight:NSFontWeightMedium];
    _maskLabel.alignment = NSTextAlignmentCenter;
    _maskLabel.frame = NSMakeRect(90, 238, 500, 22);
    _maskLabel.autoresizingMask = NSViewWidthSizable | NSViewMinYMargin | NSViewMaxYMargin;
    [_maskView addSubview:_maskLabel];

    [root addSubview:_maskView];

    NSView *bar = [[NSView alloc] initWithFrame:NSMakeRect(0, 524, 680, 36)];
    bar.autoresizingMask = NSViewWidthSizable | NSViewMinYMargin;
    NSButton *close = [NSButton buttonWithImage:[NSImage imageWithSystemSymbolName:@"xmark" accessibilityDescription:@"关闭"]
                                         target:self
                                         action:@selector(closePanel:)];
    close.bordered = NO;
    close.frame = NSMakeRect(8, 4, 28, 28);
    [bar addSubview:close];

    _originLabel = [NSTextField labelWithString:@"linux.do"];
    _originLabel.textColor = NSColor.secondaryLabelColor;
    _originLabel.font = [NSFont systemFontOfSize:12];
    _originLabel.alignment = NSTextAlignmentCenter;
    _originLabel.frame = NSMakeRect(160, 9, 360, 18);
    _originLabel.autoresizingMask = NSViewWidthSizable;
    [bar addSubview:_originLabel];

    _browserAuthBtn = [NSButton buttonWithTitle:@"切换默认浏览器一键授权 ↗"
                                         target:self
                                         action:@selector(switchToBrowserAuth:)];
    _browserAuthBtn.bordered = NO;
    _browserAuthBtn.font = [NSFont systemFontOfSize:11.5 weight:NSFontWeightMedium];
    _browserAuthBtn.contentTintColor = [NSColor colorWithRed:0.988 green:0.827 blue:0.302 alpha:1.0];
    _browserAuthBtn.frame = NSMakeRect(458, 4, 158, 28);
    _browserAuthBtn.autoresizingMask = NSViewMinXMargin;
    _browserAuthBtn.hidden = YES;
    [bar addSubview:_browserAuthBtn];

    _progress = [[NSProgressIndicator alloc] initWithFrame:NSMakeRect(620, 9, 18, 18)];
    _progress.style = NSProgressIndicatorStyleSpinning;
    _progress.controlSize = NSControlSizeSmall;
    _progress.displayedWhenStopped = NO;
    _progress.autoresizingMask = NSViewMinXMargin;
    [bar addSubview:_progress];

    NSButton *reload = [NSButton buttonWithImage:[NSImage imageWithSystemSymbolName:@"arrow.clockwise" accessibilityDescription:@"重新加载"]
                                          target:self
                                          action:@selector(reloadPage:)];
    reload.bordered = NO;
    reload.frame = NSMakeRect(644, 4, 28, 28);
    reload.autoresizingMask = NSViewMinXMargin;
    [bar addSubview:reload];

    [root addSubview:bar];
    _panel.contentView = root;
}

- (void)handleGetURLEvent:(NSAppleEventDescriptor *)event withReplyEvent:(NSAppleEventDescriptor *)replyEvent {
    NSString *urlStr = [[event paramDescriptorForKeyword:keyDirectObject] stringValue];
    if (urlStr.length > 0 && gEventReply != NULL) {
        [NSApp activateIgnoringOtherApps:YES];
        sendReply(gEventReply, 0, @{@"redirect": urlStr}, nil);
    }
}

- (void)reloadPage:(id)sender { [_web reload]; }
- (void)switchToBrowserAuth:(id)sender {
    self.cancelled = YES;
    [self hide];
    if (gEventReply != NULL) {
        sendReply(gEventReply, 0, @{@"browserAuth": @YES}, nil);
    }
}
- (void)closePanel:(id)sender { [self windowShouldClose:_panel]; }
- (void)applyMaskVisuals {
    if (!_maskView) return;
    NSColor *bg = _maskDarkState
        ? [NSColor colorWithRed:0.063 green:0.071 blue:0.090 alpha:1.0]
        : [NSColor colorWithRed:0.969 green:0.973 blue:0.980 alpha:1.0];
    _maskView.layer.backgroundColor = bg.CGColor;
    _maskLabel.textColor = _maskDarkState
        ? [NSColor colorWithRed:0.631 green:0.659 blue:0.710 alpha:1.0]
        : [NSColor colorWithRed:0.322 green:0.345 blue:0.400 alpha:1.0];
    if (_maskMessageState.length > 0) {
        _maskLabel.stringValue = _maskMessageState;
    }
    if (_maskVisibleState) {
        _maskView.hidden = NO;
        if (_panel && _panel.isVisible) {
            [_maskSpinner startAnimation:nil];
        }
    } else {
        [_maskSpinner stopAnimation:nil];
        _maskView.hidden = YES;
    }
}
- (void)setVerifyMask:(BOOL)visible dark:(BOOL)dark message:(NSString *)msg {
    _maskVisibleState = visible;
    _maskDarkState = dark;
    if (msg.length > 0) {
        _maskMessageState = [msg copy];
    }
    [self applyMaskVisuals];
}
- (void)hide {
    _maskVisibleState = NO;
    if (_maskSpinner) [_maskSpinner stopAnimation:nil];
    if (_progress) [_progress stopAnimation:nil];
    if (_maskView) _maskView.hidden = YES;
    if (_panel) {
        if (_panel.sheetParent) [_panel.sheetParent endSheet:_panel];
        [_panel orderOut:nil];
    }
    if (_web.superview) {
        [_web removeFromSuperview];
    }
}
- (BOOL)windowShouldClose:(NSWindow *)sender {
    self.cancelled = YES;
    [self hide];
    return NO;
}
- (void)show:(BOOL)dark loginMode:(BOOL)loginMode {
    self.cancelled = NO;
    [self ensurePanelCreated];
    if (_web.superview != _panelRoot) {
        [_web setFrame:NSMakeRect(0, 0, _panelRoot.bounds.size.width, MAX(100, _panelRoot.bounds.size.height - 36))];
        [_panelRoot addSubview:_web positioned:NSWindowBelow relativeTo:_maskView];
    }
    _browserAuthBtn.hidden = !loginMode;
    _originLabel.frame = loginMode ? NSMakeRect(42, 9, 408, 18) : NSMakeRect(60, 9, 560, 18);
    _originLabel.alignment = loginMode ? NSTextAlignmentLeft : NSTextAlignmentCenter;
    _panel.appearance = [NSAppearance appearanceNamed:dark ? NSAppearanceNameDarkAqua : NSAppearanceNameAqua];
    _panel.contentView.layer.backgroundColor = (dark
        ? [NSColor colorWithRed:0.063 green:0.071 blue:0.090 alpha:1.0]
        : NSColor.windowBackgroundColor).CGColor;
    [self applyMaskVisuals];
    [NSApp activateIgnoringOtherApps:YES];
    if (_panel.isVisible) {
        [_panel makeKeyAndOrderFront:nil];
        [_panel makeFirstResponder:_web];
        if (_maskVisibleState) [_maskSpinner startAnimation:nil];
        return;
    }
    NSWindow *owner = NSApp.keyWindow;
    if (!owner || owner == _panel || [owner isKindOfClass:NSPanel.class]) {
        owner = nil;
        for (NSWindow *candidate in NSApp.orderedWindows) {
            if (candidate != _panel && candidate.isVisible && ![candidate isKindOfClass:NSPanel.class]) {
                owner = candidate;
                break;
            }
        }
    }
    if (owner && owner != _panel && !owner.attachedSheet) {
        owner.appearance = _panel.appearance;
        [_panel setContentSize:NSMakeSize(MAX(380, MIN(680, owner.frame.size.width - 48)), MAX(400, MIN(560, owner.frame.size.height - 80)))];
        [owner beginSheet:_panel completionHandler:nil];
        [_panel makeFirstResponder:_web];
    } else {
        [_panel center];
        [_panel makeKeyAndOrderFront:nil];
        [_panel makeFirstResponder:_web];
    }
    if (_maskVisibleState) [_maskSpinner startAnimation:nil];
}

- (void)webView:(WKWebView *)webView didStartProvisionalNavigation:(WKNavigation *)nav {
    self.navigationError = nil;
    self.processTerminated = NO;
    if (_panel && _panel.isVisible) {
        [_progress startAnimation:nil];
    }
}
- (void)webView:(WKWebView *)webView didCommitNavigation:(WKNavigation *)nav {
    if (_panel && _panel.isVisible) {
        NSString *host = webView.URL.host ?: @"linux.do";
        _panel.title = host;
        _originLabel.stringValue = host;
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(900 * NSEC_PER_MSEC)), dispatch_get_main_queue(), ^{
            if (!self.panel || !self.panel.isVisible) return;
            [webView evaluateJavaScript:@"Boolean(document.querySelector('meta[name=\"csrf-token\"]') || document.getElementById('data-preloaded'))"
                      completionHandler:^(id res, NSError *err) {
                if (!err && [res respondsToSelector:@selector(boolValue)] && [res boolValue]) {
                    [self.progress stopAnimation:nil];
                }
            }];
        });
    }
}
- (void)webView:(WKWebView *)webView didFinishNavigation:(WKNavigation *)nav {
    if (_progress) [_progress stopAnimation:nil];
    if (_panel && _panel.isVisible) {
        NSString *host = webView.URL.host ?: @"linux.do";
        _panel.title = host;
        _originLabel.stringValue = host;
    }
}
- (void)webViewWebContentProcessDidTerminate:(WKWebView *)webView {
    if (_progress) [_progress stopAnimation:nil];
    self.processTerminated = YES;
    NSURL *recoverUrl = [NSURL URLWithString:@"https://linux.do/about"];
    if (recoverUrl) {
        [webView loadRequest:[NSURLRequest requestWithURL:recoverUrl]];
    }
}
- (void)failNavigation:(NSError *)error {
    if (_progress) [_progress stopAnimation:nil];
    if ([error.domain isEqualToString:NSURLErrorDomain] && error.code == NSURLErrorCancelled) return;
    if ([error.domain isEqualToString:@"WebKitErrorDomain"] && error.code == 102) return;
    self.navigationError = error.localizedDescription ?: @"Navigation failed";
}
- (void)webView:(WKWebView *)webView didFailNavigation:(WKNavigation *)nav withError:(NSError *)error { [self failNavigation:error]; }
- (void)webView:(WKWebView *)webView didFailProvisionalNavigation:(WKNavigation *)nav withError:(NSError *)error { [self failNavigation:error]; }

- (void)webView:(WKWebView *)webView decidePolicyForNavigationAction:(WKNavigationAction *)action decisionHandler:(void (^)(WKNavigationActionPolicy))decision {
    if (action.targetFrame && !action.targetFrame.isMainFrame) {
        decision(WKNavigationActionPolicyAllow);
        return;
    }
    NSURL *url = action.request.URL;
    NSString *scheme = url.scheme.lowercaseString;
    if ([scheme isEqualToString:@"discourse"] || [scheme isEqualToString:@"lindo"]) {
        if (gEventReply) sendReply(gEventReply, 0, @{@"redirect": url.absoluteString}, nil);
        [self hide];
        decision(WKNavigationActionPolicyCancel);
        return;
    }
    BOOL allowed = [scheme isEqualToString:@"https"] || [url.absoluteString isEqualToString:@"about:blank"];
    if (allowed && action.targetFrame.isMainFrame && url.host.length && _panel && _panel.isVisible) {
        _panel.title = url.host;
        _originLabel.stringValue = url.host;
    }
    decision(allowed ? WKNavigationActionPolicyAllow : WKNavigationActionPolicyCancel);
}

- (WKWebView *)webView:(WKWebView *)webView createWebViewWithConfiguration:(WKWebViewConfiguration *)configuration forNavigationAction:(WKNavigationAction *)action windowFeatures:(WKWindowFeatures *)features {
    if (!action.targetFrame && [action.request.URL.scheme.lowercaseString isEqualToString:@"https"]) {
        [webView loadRequest:action.request];
    }
    return nil;
}
@end

static LindoBridgeHost *gHost = nil;

@interface LindoBrowseWindow : NSWindow
@property (weak) WKWebView *webRef;
@end
@implementation LindoBrowseWindow
- (BOOL)performKeyEquivalent:(NSEvent *)event {
    NSEventModifierFlags flags = event.modifierFlags & NSEventModifierFlagDeviceIndependentFlagsMask;
    NSString *key = event.charactersIgnoringModifiers.lowercaseString;
    if (flags == NSEventModifierFlagCommand) {
        if ([key isEqualToString:@"w"]) { [self performClose:nil]; return YES; }
        if ([key isEqualToString:@"r"]) { [self.webRef reload]; return YES; }
        if ([key isEqualToString:@"["]) { if (self.webRef.canGoBack) [self.webRef goBack]; return YES; }
        if ([key isEqualToString:@"]"]) { if (self.webRef.canGoForward) [self.webRef goForward]; return YES; }
        if ([key isEqualToString:@"x"]) return [NSApp sendAction:@selector(cut:) to:nil from:self];
        if ([key isEqualToString:@"c"]) return [NSApp sendAction:@selector(copy:) to:nil from:self];
        if ([key isEqualToString:@"v"]) return [NSApp sendAction:@selector(paste:) to:nil from:self];
        if ([key isEqualToString:@"a"]) return [NSApp sendAction:@selector(selectAll:) to:nil from:self];
        if ([key isEqualToString:@"z"]) return [NSApp sendAction:NSSelectorFromString(@"undo:") to:nil from:self];
    } else if (flags == (NSEventModifierFlagCommand | NSEventModifierFlagShift)) {
        if ([key isEqualToString:@"z"]) return [NSApp sendAction:NSSelectorFromString(@"redo:") to:nil from:self];
    }
    return [super performKeyEquivalent:event];
}
@end

@interface LindoBrowseHost : NSObject <WKNavigationDelegate, WKUIDelegate, NSWindowDelegate>
@property WKWebView *web;
@property LindoBrowseWindow *window;
@property NSProgressIndicator *progress;
@property NSTextField *titleLabel;
@property NSButton *backBtn;
@property NSButton *fwdBtn;
@end

@implementation LindoBrowseHost
- (instancetype)init {
    self = [super init];
    if (self) {
        WKWebsiteDataStore *store = WKWebsiteDataStore.defaultDataStore;
        WKWebViewConfiguration *config = [WKWebViewConfiguration new];
        config.websiteDataStore = store;

        NSRect initRect = NSMakeRect(0, 0, 1120, 780);
        _web = [[WKWebView alloc] initWithFrame:NSMakeRect(0, 0, 1120, 736) configuration:config];
        _web.navigationDelegate = self;
        _web.UIDelegate = self;
        _web.allowsBackForwardNavigationGestures = YES;
        _web.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;

        _window = [[LindoBrowseWindow alloc]
            initWithContentRect:initRect
                      styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable | NSWindowStyleMaskMiniaturizable | NSWindowStyleMaskResizable | NSWindowStyleMaskFullSizeContentView
                        backing:NSBackingStoreBuffered
                          defer:NO];
        _window.webRef = _web;
        _window.releasedWhenClosed = NO;
        _window.delegate = self;
        _window.titleVisibility = NSWindowTitleHidden;
        _window.titlebarAppearsTransparent = YES;
        _window.minSize = NSMakeSize(720, 520);

        NSView *root = [[NSView alloc] initWithFrame:initRect];
        root.wantsLayer = YES;
        root.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
        [_web setFrame:NSMakeRect(0, 0, 1120, 736)];
        [root addSubview:_web];

        NSView *bar = [[NSView alloc] initWithFrame:NSMakeRect(0, 736, 1120, 44)];
        bar.autoresizingMask = NSViewWidthSizable | NSViewMinYMargin;

        _backBtn = [NSButton buttonWithImage:[NSImage imageWithSystemSymbolName:@"chevron.left" accessibilityDescription:@"后退"]
                                      target:self
                                      action:@selector(goBack:)];
        _backBtn.bordered = NO;
        _backBtn.frame = NSMakeRect(84, 8, 28, 28);
        _backBtn.enabled = NO;
        [bar addSubview:_backBtn];

        _fwdBtn = [NSButton buttonWithImage:[NSImage imageWithSystemSymbolName:@"chevron.right" accessibilityDescription:@"前进"]
                                     target:self
                                     action:@selector(goForward:)];
        _fwdBtn.bordered = NO;
        _fwdBtn.frame = NSMakeRect(114, 8, 28, 28);
        _fwdBtn.enabled = NO;
        [bar addSubview:_fwdBtn];

        _titleLabel = [NSTextField labelWithString:@"LINUX DO"];
        _titleLabel.textColor = NSColor.secondaryLabelColor;
        _titleLabel.font = [NSFont systemFontOfSize:12.5 weight:NSFontWeightMedium];
        _titleLabel.alignment = NSTextAlignmentCenter;
        _titleLabel.lineBreakMode = NSLineBreakByTruncatingTail;
        _titleLabel.frame = NSMakeRect(160, 13, 800, 18);
        _titleLabel.autoresizingMask = NSViewWidthSizable;
        [bar addSubview:_titleLabel];

        _progress = [[NSProgressIndicator alloc] initWithFrame:NSMakeRect(1016, 13, 16, 16)];
        _progress.style = NSProgressIndicatorStyleSpinning;
        _progress.controlSize = NSControlSizeSmall;
        _progress.displayedWhenStopped = NO;
        _progress.autoresizingMask = NSViewMinXMargin;
        [bar addSubview:_progress];

        NSButton *reload = [NSButton buttonWithImage:[NSImage imageWithSystemSymbolName:@"arrow.clockwise" accessibilityDescription:@"刷新"]
                                              target:self
                                              action:@selector(reloadPage:)];
        reload.bordered = NO;
        reload.frame = NSMakeRect(1042, 8, 28, 28);
        reload.autoresizingMask = NSViewMinXMargin;
        [bar addSubview:reload];

        NSButton *openExt = [NSButton buttonWithImage:[NSImage imageWithSystemSymbolName:@"safari" accessibilityDescription:@"在默认浏览器中打开"]
                                               target:self
                                               action:@selector(openExternal:)];
        openExt.bordered = NO;
        openExt.toolTip = @"在系统默认浏览器中打开";
        openExt.frame = NSMakeRect(1078, 8, 28, 28);
        openExt.autoresizingMask = NSViewMinXMargin;
        [bar addSubview:openExt];

        [root addSubview:bar];
        _window.contentView = root;
    }
    return self;
}

- (void)syncNavState {
    _backBtn.enabled = _web.canGoBack;
    _fwdBtn.enabled = _web.canGoForward;
    NSString *pageTitle = _web.title;
    NSString *host = _web.URL.host ?: @"linux.do";
    if (pageTitle.length > 0) {
        _titleLabel.stringValue = [NSString stringWithFormat:@"%@ · %@", pageTitle, host];
    } else {
        _titleLabel.stringValue = host;
    }
}

- (void)goBack:(id)sender { if (_web.canGoBack) [_web goBack]; }
- (void)goForward:(id)sender { if (_web.canGoForward) [_web goForward]; }
- (void)reloadPage:(id)sender { [_web reload]; }
- (void)openExternal:(id)sender {
    NSURL *u = _web.URL;
    if (u) [[NSWorkspace sharedWorkspace] openURL:u];
}

- (void)openUrl:(NSURL *)url dark:(BOOL)dark {
    NSColor *bg = dark
        ? [NSColor colorWithRed:0.063 green:0.071 blue:0.090 alpha:1.0]
        : [NSColor colorWithRed:0.97 green:0.975 blue:0.985 alpha:1.0];
    _window.appearance = [NSAppearance appearanceNamed:dark ? NSAppearanceNameDarkAqua : NSAppearanceNameAqua];
    _window.contentView.layer.backgroundColor = bg.CGColor;
    if (@available(macOS 12.0, *)) {
        if ([_web respondsToSelector:@selector(setUnderPageBackgroundColor:)]) {
            [_web setUnderPageBackgroundColor:bg];
        }
    }
    _titleLabel.stringValue = url.host ?: @"linux.do";
    [_web loadRequest:[NSURLRequest requestWithURL:url]];
    if (!_window.isVisible) {
        NSScreen *screen = NSScreen.mainScreen;
        if (screen) {
            NSRect vis = screen.visibleFrame;
            CGFloat w = MIN(1160.0, MAX(820.0, vis.size.width * 0.80));
            CGFloat h = MIN(820.0, MAX(580.0, vis.size.height * 0.84));
            [_window setFrame:NSMakeRect(
                vis.origin.x + (vis.size.width - w) * 0.5,
                vis.origin.y + (vis.size.height - h) * 0.5,
                w,
                h
            ) display:YES];
        } else {
            [_window center];
        }
    }
    [NSApp activateIgnoringOtherApps:YES];
    [_window makeKeyAndOrderFront:nil];
    [_window makeFirstResponder:_web];
}

- (void)webView:(WKWebView *)webView didStartProvisionalNavigation:(WKNavigation *)nav {
    [_progress startAnimation:nil];
    [self syncNavState];
}
- (void)webView:(WKWebView *)webView didCommitNavigation:(WKNavigation *)nav {
    [self syncNavState];
}
- (void)webView:(WKWebView *)webView didFinishNavigation:(WKNavigation *)nav {
    [_progress stopAnimation:nil];
    [self syncNavState];
}
- (void)webView:(WKWebView *)webView didFailNavigation:(WKNavigation *)nav withError:(NSError *)error {
    [_progress stopAnimation:nil];
    [self syncNavState];
}
- (void)webView:(WKWebView *)webView didFailProvisionalNavigation:(WKNavigation *)nav withError:(NSError *)error {
    [_progress stopAnimation:nil];
    [self syncNavState];
}
- (void)webView:(WKWebView *)webView decidePolicyForNavigationAction:(WKNavigationAction *)action decisionHandler:(void (^)(WKNavigationActionPolicy))decision {
    NSURL *url = action.request.URL;
    NSString *scheme = url.scheme.lowercaseString;
    if ([scheme isEqualToString:@"discourse"] || [scheme isEqualToString:@"lindo"]) {
        if (gEventReply) sendReply(gEventReply, 0, @{@"redirect": url.absoluteString}, nil);
        [_window orderOut:nil];
        decision(WKNavigationActionPolicyCancel);
        return;
    }
    decision(WKNavigationActionPolicyAllow);
}
- (WKWebView *)webView:(WKWebView *)webView createWebViewWithConfiguration:(WKWebViewConfiguration *)configuration forNavigationAction:(WKNavigationAction *)action windowFeatures:(WKWindowFeatures *)features {
    if (!action.targetFrame && action.request.URL) {
        [webView loadRequest:action.request];
    }
    return nil;
}
@end

static LindoBrowseHost *gBrowser = nil;

/* ==================== 3b. Offscreen Connect Probe (Zero Interference with gHost.web) ==================== */
@interface LindoConnectProbe : NSObject <WKNavigationDelegate>
@property (nonatomic, strong) WKWebView *web;
@property (nonatomic, assign) LindoBridgeReply callback;
@property (nonatomic, assign) long long reqId;
@property (nonatomic, assign) BOOL finished;
- (instancetype)initWithCallback:(LindoBridgeReply)cb reqId:(long long)reqId;
- (void)start;
@end

static NSMutableSet<LindoConnectProbe *> *gActiveConnectProbes = nil;

@implementation LindoConnectProbe
- (instancetype)initWithCallback:(LindoBridgeReply)cb reqId:(long long)reqId {
    if ((self = [super init])) {
        _callback = cb;
        _reqId = reqId;
        _finished = NO;
        WKWebViewConfiguration *cfg = [[WKWebViewConfiguration alloc] init];
        cfg.websiteDataStore = [WKWebsiteDataStore defaultDataStore];
        _web = [[WKWebView alloc] initWithFrame:NSMakeRect(0, 0, 1024, 768) configuration:cfg];
        _web.navigationDelegate = self;
    }
    return self;
}

- (void)finishWithHtml:(NSString *)html {
    if (_finished) return;
    _finished = YES;
    [_web stopLoading];
    _web.navigationDelegate = nil;
    sendReply(_callback, _reqId, @{@"html": html ?: @""}, nil);
    [gActiveConnectProbes removeObject:self];
}

- (void)pollTick:(NSInteger)tick {
    if (_finished) return;
    if (tick >= 28) {
        [self finishWithHtml:@""];
        return;
    }
    NSString *js = @"(() => {"
        "const h = (location && location.hostname) || '';"
        "const isChallenge = Boolean(document.querySelector('#challenge-running, #challenge-form, .cf-turnstile, iframe[src*=\"challenges.cloudflare.com\"]'));"
        "const hasMarkers = h === 'connect.linux.do' && Boolean(document.querySelector('.tl3-ring, .tl3-bar-item, .tl3-quota-card, .card-title, table'));"
        "return { host: h, isChallenge, hasMarkers, html: hasMarkers && document.documentElement ? document.documentElement.outerHTML : '' };"
    "})()";
    __weak typeof(self) weakSelf = self;
    [_web evaluateJavaScript:js completionHandler:^(id res, NSError *err) {
        __strong typeof(weakSelf) strongSelf = weakSelf;
        if (!strongSelf || strongSelf.finished) return;
        if (!err && [res isKindOfClass:[NSDictionary class]]) {
            NSDictionary *dict = (NSDictionary *)res;
            if ([dict[@"isChallenge"] boolValue]) {
                [strongSelf finishWithHtml:@""];
                return;
            }
            if ([dict[@"hasMarkers"] boolValue]) {
                NSString *html = [dict[@"html"] isKindOfClass:[NSString class]] ? dict[@"html"] : @"";
                if (html.length > 0) {
                    [strongSelf finishWithHtml:html];
                    return;
                }
            }
        }
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(280 * NSEC_PER_MSEC)), dispatch_get_main_queue(), ^{
            [weakSelf pollTick:tick + 1];
        });
    }];
}

- (void)start {
    if (!gActiveConnectProbes) {
        gActiveConnectProbes = [NSMutableSet new];
    }
    [gActiveConnectProbes addObject:self];
    NSURL *url = [NSURL URLWithString:@"https://connect.linux.do/"];
    NSURLRequest *req = [NSURLRequest requestWithURL:url
                                         cachePolicy:NSURLRequestReloadIgnoringLocalCacheData
                                     timeoutInterval:10.0];
    [_web loadRequest:req];
    __weak typeof(self) weakSelf = self;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(350 * NSEC_PER_MSEC)), dispatch_get_main_queue(), ^{
        [weakSelf pollTick:0];
    });
}

- (void)webView:(WKWebView *)webView decidePolicyForNavigationAction:(WKNavigationAction *)action decisionHandler:(void (^)(WKNavigationActionPolicy))decision {
    NSURL *u = action.request.URL;
    NSString *host = u.host.lowercaseString ?: @"";
    if ([u.scheme.lowercaseString isEqualToString:@"https"] && isForumHost(host)) {
        decision(WKNavigationActionPolicyAllow);
    } else if ([u.absoluteString isEqualToString:@"about:blank"]) {
        decision(WKNavigationActionPolicyAllow);
    } else {
        decision(WKNavigationActionPolicyCancel);
        [self finishWithHtml:@""];
    }
}
@end

static NSHashTable<WKWebView *> *gUnlockedWebViews = nil;
static id gScrollWheelMonitor = nil;
static BOOL gSmoothWheelJsReady = NO;
static CFAbsoluteTime gLastZeroPhaseWheelTime = 0;
static NSInteger gSmoothDaemonBurstCount = 0;

static void unlockHighRefreshRateForWebView(WKWebView *wv) {
    if (!wv) return;
    if (!gUnlockedWebViews) {
        gUnlockedWebViews = [NSHashTable weakObjectsHashTable];
    }
    if ([gUnlockedWebViews containsObject:wv]) {
        return;
    }
    [gUnlockedWebViews addObject:wv];

    WKPreferences *prefs = wv.configuration.preferences;
    if (!prefs) return;

    SEL featSel = NSSelectorFromString(@"_features");
    SEL setSel = NSSelectorFromString(@"_setEnabled:forFeature:");
    if ([WKPreferences respondsToSelector:featSel] && [prefs respondsToSelector:setSel]) {
        #pragma clang diagnostic push
        #pragma clang diagnostic ignored "-Warc-performSelector-leaks"
        NSArray *features = [WKPreferences performSelector:featSel];
        NSMethodSignature *sig = [prefs methodSignatureForSelector:setSel];
        if (sig) {
            for (id feat in features) {
                id featVar = feat;
                NSString *key = [featVar valueForKey:@"key"];
                if ([key isEqualToString:@"PreferPageRenderingUpdatesNear60FPSEnabled"] ||
                    [key isEqualToString:@"WebAnimationsCustomFrameRateEnabled"]) {
                    BOOL enable = [key isEqualToString:@"WebAnimationsCustomFrameRateEnabled"];
                    NSInvocation *inv = [NSInvocation invocationWithMethodSignature:sig];
                    inv.target = prefs;
                    inv.selector = setSel;
                    [inv setArgument:&enable atIndex:2];
                    [inv setArgument:&featVar atIndex:3];
                    [inv invoke];
                }
            }
        }
        #pragma clang diagnostic pop
    }
}

static WKWebView *findFirstWKWebView(NSView *view) {
    if (!view) return nil;
    if ([view isKindOfClass:[WKWebView class]]) {
        return (WKWebView *)view;
    }
    for (NSView *sub in view.subviews) {
        WKWebView *found = findFirstWKWebView(sub);
        if (found) return found;
    }
    return nil;
}

static id gInactiveHoverGuardMonitor = nil;

static void unlockAllAppWebViews(void) {
    for (NSWindow *win in [NSApp windows]) {
        // Never unlock 144Hz/240Hz display updates on the hidden background network bridge panel (`gHost.panel`)!
        if (gHost && win == gHost.panel) continue;
        // Guarantee background clicks/drags never initiate AppKit window moves during screenshot selection!
        win.movableByWindowBackground = NO;
        WKWebView *wv = findFirstWKWebView(win.contentView);
        if (wv) {
            unlockHighRefreshRateForWebView(wv);
        }
    }
    if (gBrowser && gBrowser.web) unlockHighRefreshRateForWebView(gBrowser.web);
}

static double mapDiscreteWheelLinesToPixels(double lines) {
    if (lines == 0.0) return 0.0;
    double mag = fabs(lines);
    // Normalize macOS raw USB HID single-notch tiny deltas (0.1..1.0 lines) to a crisp ~54..70px glide,
    // while preserving multi-notch hardware acceleration up to 380px per impulse.
    double px = (mag <= 1.0) ? (52.0 + mag * 18.0) : (70.0 + (mag - 1.0) * 44.0);
    if (px > 380.0) px = 380.0;
    return (lines < 0.0) ? px : -px;
}

static void installHighRefreshAndSmoothWheelMonitor(void) {
    unlockAllAppWebViews();
    if (!gInactiveHoverGuardMonitor) {
        // Prevent WKWebView's `NSTrackingActiveAlways` tracking areas from overriding `NSCursor` (e.g. `col-resize` on the split seam)
        // or popping up native `NSToolTipPanel` windows while external screenshot overlays (macOS / Feishu / WeChat / Snipaste) are active!
        NSEventMask hoverMask = NSEventMaskMouseMoved | NSEventMaskCursorUpdate | NSEventMaskMouseEntered | NSEventMaskMouseExited;
        gInactiveHoverGuardMonitor = [NSEvent addLocalMonitorForEventsMatchingMask:hoverMask handler:^NSEvent * _Nullable(NSEvent * _Nonnull event) {
            if (!NSApp.isActive) {
                return nil;
            }
            NSWindow *win = event.window;
            if (win && !win.isKeyWindow) {
                return nil;
            }
            return event;
        }];
    }
    if (gScrollWheelMonitor) return;

    gScrollWheelMonitor = [NSEvent addLocalMonitorForEventsMatchingMask:NSEventMaskScrollWheel handler:^NSEvent * _Nullable(NSEvent * _Nonnull event) {
        NSWindow *win = event.window;
        if (!win) return event;

        WKWebView *wv = findFirstWKWebView(win.contentView);
        if (!wv) return event;
        unlockHighRefreshRateForWebView(wv);

        // Keep auxiliary verification / forum browser panels on standard webpage wheel behavior
        if ((gHost && win == gHost.panel) || (gBrowser && win == gBrowser.window)) {
            return event;
        }
        if (!gSmoothWheelJsReady) {
            return event;
        }

        // Never intercept zoom/modified scroll gestures
        NSEventModifierFlags mods = event.modifierFlags & NSEventModifierFlagDeviceIndependentFlagsMask;
        if (mods & (NSEventModifierFlagCommand | NSEventModifierFlagControl | NSEventModifierFlagOption)) {
            return event;
        }

        // Apple Magic Trackpad & Magic Mouse ALWAYS carry gesture phase or momentumPhase:
        // pass straight through to WebKit's unlocked 144Hz C++ AsyncOverflowScrolling compositor!
        if (event.phase != NSEventPhaseNone || event.momentumPhase != NSEventPhaseNone) {
            return event;
        }

        CFAbsoluteTime now = CFAbsoluteTimeGetCurrent();
        double dtMs = (now - gLastZeroPhaseWheelTime) * 1000.0;
        gLastZeroPhaseWheelTime = now;

        double cssDeltaX = 0.0;
        double cssDeltaY = 0.0;

        if (!event.hasPreciseScrollingDeltas) {
            // Raw physical stepped mouse wheel (hasPreciseScrollingDeltas == NO)
            double linesY = (double)event.scrollingDeltaY;
            if (linesY == 0.0) linesY = (double)event.deltaY;
            double linesX = (double)event.scrollingDeltaX;
            if (linesX == 0.0) linesX = (double)event.deltaX;
            if ((mods & NSEventModifierFlagShift) && linesX == 0.0 && linesY != 0.0) {
                linesX = linesY;
                linesY = 0.0;
            }
            cssDeltaY = mapDiscreteWheelLinesToPixels(linesY);
            cssDeltaX = mapDiscreteWheelLinesToPixels(linesX);
        } else {
            // Pixel-mode event with zero phase: distinguish 60-144Hz smooth-scroll daemons (Mos / Mac Mouse Fix)
            // from stepped pixel-chunk mouse drivers (e.g. Logi / gaming mice without smooth interpolation)
            if (dtMs < 22.0) {
                gSmoothDaemonBurstCount++;
            } else if (dtMs > 90.0) {
                gSmoothDaemonBurstCount = 0;
            }
            double absY = fabs((double)event.scrollingDeltaY);
            double absX = fabs((double)event.scrollingDeltaX);
            if (gSmoothDaemonBurstCount >= 1 || (absY < 24.0 && absX < 24.0)) {
                return event;
            }
            cssDeltaY = -(double)event.scrollingDeltaY;
            cssDeltaX = -(double)event.scrollingDeltaX;
        }

        if (fabs(cssDeltaX) < 0.1 && fabs(cssDeltaY) < 0.1) {
            return event;
        }

        NSPoint ptInWeb = [wv convertPoint:event.locationInWindow fromView:nil];
        CGFloat cssX = ptInWeb.x;
        CGFloat cssY = wv.isFlipped ? ptInWeb.y : (wv.bounds.size.height - ptInWeb.y);
        if (cssX < 0 || cssY < 0 || cssX > wv.bounds.size.width || cssY > wv.bounds.size.height) {
            return event;
        }

        NSString *js = [NSString stringWithFormat:@"window.__lindoSmoothWheel&&window.__lindoSmoothWheel(%.1f,%.1f,%.2f,%.2f)", cssX, cssY, cssDeltaX, cssDeltaY];
        [wv evaluateJavaScript:js completionHandler:nil];
        return nil;
    }];
}

static void registerDefaultUrlSchemesForCurrentBundle(void) {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        NSBundle *mainBundle = [NSBundle mainBundle];
        NSURL *bundleUrl = mainBundle.bundleURL;
        if (bundleUrl && [bundleUrl.path hasSuffix:@".app"]) {
            LSRegisterURL((__bridge CFURLRef)bundleUrl, true);
        }
        NSString *bundleId = mainBundle.bundleIdentifier;
        if (bundleId.length > 0) {
            #pragma clang diagnostic push
            #pragma clang diagnostic ignored "-Wdeprecated-declarations"
            LSSetDefaultHandlerForURLScheme(CFSTR("discourse"), (__bridge CFStringRef)bundleId);
            LSSetDefaultHandlerForURLScheme(CFSTR("lindo"), (__bridge CFStringRef)bundleId);
            #pragma clang diagnostic pop
        }
    });
}

__attribute__((visibility("default"))) void lindo_bridge_enable_high_refresh(int jsReady) {
    dispatch_async(dispatch_get_main_queue(), ^{
        registerDefaultUrlSchemesForCurrentBundle();
        if (jsReady) {
            gSmoothWheelJsReady = YES;
        }
        installHighRefreshAndSmoothWheelMonitor();
    });
}

static void purgeSharedStorageAuthCookies(BOOL includePendingOAuth) {
    NSHTTPCookieStorage *shared = [NSHTTPCookieStorage sharedHTTPCookieStorage];
    NSArray<NSHTTPCookie *> *all = [shared.cookies copy];
    NSSet<NSString *> *authNames = includePendingOAuth
        ? [NSSet setWithObjects:@"_t", @"_forum_session", @"authentication_data", nil]
        : [NSSet setWithObjects:@"_t", @"_forum_session", nil];
    for (NSHTTPCookie *c in all) {
        NSString *dom = [c.domain hasPrefix:@"."] ? [c.domain substringFromIndex:1] : c.domain;
        if ([ dom.lowercaseString isEqualToString:@"linux.do"] && [authNames containsObject:c.name]) {
            [shared deleteCookie:c];
        }
    }
}

static BOOL isCloudflareDomainCookieName(NSString *name) {
    if (!name.length) return NO;
    return [name isEqualToString:@"cf_clearance"] ||
           [name isEqualToString:@"__cf_bm"] ||
           [name isEqualToString:@"_cfuvid"] ||
           [name isEqualToString:@"cf_chl_rc_i"] ||
           [name isEqualToString:@"cf_chl_rc_ni"];
}

static NSHTTPCookie *makeHostOnlyForumCookie(NSString *name, NSString *value, BOOL persistent) {
    if (!name.length || !value.length) return nil;
    NSURL *originUrl = [NSURL URLWithString:@"https://linux.do/"];
    NSString *maxAgePart = persistent ? @"; Max-Age=5184000" : @"";
    NSString *header = [NSString stringWithFormat:@"%@=%@; Path=/%@; Secure; HttpOnly; SameSite=Lax", name, value, maxAgePart];
    NSArray<NSHTTPCookie *> *parsed = [NSHTTPCookie cookiesWithResponseHeaderFields:@{@"Set-Cookie": header} forURL:originUrl];
    if (parsed.count > 0) {
        return parsed.firstObject;
    }
    // Fallback without NSHTTPCookieDomain so Foundation infers host-only from OriginURL
    NSMutableDictionary *props = [NSMutableDictionary dictionaryWithDictionary:@{
        NSHTTPCookieName: name,
        NSHTTPCookieValue: value,
        NSHTTPCookieOriginURL: originUrl,
        NSHTTPCookiePath: @"/",
        NSHTTPCookieSecure: @"TRUE",
        @"HttpOnly": @"TRUE"
    }];
    if (persistent) {
        props[NSHTTPCookieExpires] = [NSDate dateWithTimeIntervalSinceNow:86400 * 60];
    }
    return [NSHTTPCookie cookieWithProperties:props];
}

__attribute__((visibility("default"))) void lindo_bridge_set_event_callback(LindoBridgeReply callback) {
    gEventReply = callback;
}

__attribute__((visibility("default"))) void lindo_bridge_command(long long request, const char *raw, LindoBridgeReply callback) {
    NSString *input = raw ? [NSString stringWithUTF8String:raw] : @"{}";
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!gHost) gHost = [LindoBridgeHost new];
        NSDictionary *c = [NSJSONSerialization JSONObjectWithData:[input dataUsingEncoding:NSUTF8StringEncoding] options:0 error:nil];
        NSString *method = c[@"method"] ?: @"";
        NSDictionary *p = c[@"params"] ?: @{};
        WKHTTPCookieStore *store = gHost.web.configuration.websiteDataStore.httpCookieStore;

        if ([method isEqualToString:@"init"]) {
            gHost.cancelled = NO;
            registerDefaultUrlSchemesForCurrentBundle();
            purgeSharedStorageAuthCookies(NO);
            installHighRefreshAndSmoothWheelMonitor();
            sendReply(callback, request, @{}, nil);
        } else if ([method isEqualToString:@"openBrowser"]) {
            NSURL *url = [NSURL URLWithString:p[@"url"] ?: @""];
            BOOL valid = [url.scheme isEqualToString:@"https"] && isForumHost(url.host);
            if (!valid) {
                sendReply(callback, request, nil, @"Invalid browser URL");
                return;
            }
            if (!gBrowser) gBrowser = [LindoBrowseHost new];
            [gBrowser openUrl:url dark:[p[@"dark"] boolValue]];
            sendReply(callback, request, @{}, nil);
        } else if ([method isEqualToString:@"show"]) {
            [gHost show:[p[@"dark"] boolValue] loginMode:[p[@"login"] boolValue]];
            sendReply(callback, request, @{}, nil);
        } else if ([method isEqualToString:@"hide"]) {
            [gHost hide];
            sendReply(callback, request, @{}, nil);
        } else if ([method isEqualToString:@"setVerifyMask"]) {
            BOOL visible = [p[@"visible"] boolValue];
            BOOL dark = p[@"dark"] ? [p[@"dark"] boolValue] : YES;
            NSString *msg = [p[@"message"] isKindOfClass:NSString.class] ? p[@"message"] : @"正在连接安全验证服务…";
            [gHost setVerifyMask:visible dark:dark message:msg];
            sendReply(callback, request, @{}, nil);
        } else if ([method isEqualToString:@"status"]) {
            sendReply(callback, request, @{
                @"cancelled": @(gHost.cancelled),
                @"loading": @(gHost.web.loading),
                @"processTerminated": @(gHost.processTerminated),
                @"error": gHost.navigationError ?: @""
            }, nil);
        } else if ([method isEqualToString:@"setDockBadge"]) {
            NSString *label = [p[@"label"] isKindOfClass:NSString.class] ? p[@"label"] : @"";
            [NSApp.dockTile setBadgeLabel:label.length > 0 ? label : nil];
            sendReply(callback, request, @{}, nil);
        } else if ([method isEqualToString:@"navigate"]) {
            NSURL *url = [NSURL URLWithString:p[@"url"] ?: @""];
            BOOL valid = ([url.scheme isEqualToString:@"https"] && isForumHost(url.host)) ||
                         [url.absoluteString isEqualToString:@"about:blank"];
            if (!valid) {
                sendReply(callback, request, nil, @"Invalid navigation target");
                return;
            }
            gHost.cancelled = NO;
            gHost.navigationError = nil;
            gHost.processTerminated = NO;
            [gHost.web loadRequest:[NSURLRequest requestWithURL:url]];
            sendReply(callback, request, @{}, nil);
        } else if ([method isEqualToString:@"evaluate"]) {
            NSString *body = [NSString stringWithFormat:@"return await (%@);", p[@"script"] ?: @"null"];
            BOOL isolated = [p[@"isolated"] boolValue];
            WKContentWorld *world = isolated ? WKContentWorld.defaultClientWorld : WKContentWorld.pageWorld;
            [gHost.web callAsyncJavaScript:body
                                 arguments:@{}
                                   inFrame:nil
                            inContentWorld:world
                         completionHandler:^(id result, NSError *error) {
                NSString *failure = nil;
                if (error) {
                    NSString *jsMsg = error.userInfo[@"WKJavaScriptExceptionMessage"];
                    failure = jsMsg.length > 0
                        ? [NSString stringWithFormat:@"WebKit JS exception (%@:%ld): %@", error.domain, (long)error.code, jsMsg]
                        : [NSString stringWithFormat:@"WebKit operation failed (%@:%ld)", error.domain, (long)error.code];
                }
                sendReply(callback, request, result, failure);
            }];
        } else if ([method isEqualToString:@"cookies"]) {
            purgeSharedStorageAuthCookies(NO);
            [store getAllCookies:^(NSArray<NSHTTPCookie *> *cookies) {
                NSMutableArray *result = [NSMutableArray new];
                for (NSHTTPCookie *cookie in cookies) {
                    NSString *domain = [cookie.domain hasPrefix:@"."] ? [cookie.domain substringFromIndex:1] : cookie.domain;
                    if (isForumHost(domain)) {
                        BOOL hostOnly = ![cookie.domain hasPrefix:@"."];
                        [result addObject:@{
                            @"name": cookie.name,
                            @"value": cookie.value,
                            @"domain": cookie.domain,
                            @"path": cookie.path,
                            @"hostOnly": @(hostOnly)
                        }];
                    }
                }
                sendReply(callback, request, @{@"cookies": result}, nil);
            }];
        } else if ([method isEqualToString:@"setCookies"]) {
            purgeSharedStorageAuthCookies(NO);
            NSArray<NSDictionary *> *incoming = [p[@"cookies"] isKindOfClass:NSArray.class] ? p[@"cookies"] : @[];
            NSMutableSet<NSString *> *targetNames = [NSMutableSet new];
            for (NSDictionary *item in incoming) {
                NSString *n = item[@"name"];
                // Site-owned Cloudflare cookies remain 100% managed by WKHTTPCookieStore; never delete or synthesize them.
                if ([n isKindOfClass:NSString.class] && n.length > 0 && !isCloudflareDomainCookieName(n)) {
                    [targetNames addObject:n];
                }
            }
            if (!targetNames.count) {
                sendReply(callback, request, @{}, nil);
                return;
            }
            // 1. Delete existing variants for target Discourse session cookie names first
            [store getAllCookies:^(NSArray<NSHTTPCookie *> *existing) {
                dispatch_group_t delGroup = dispatch_group_create();
                for (NSHTTPCookie *c in existing) {
                    NSString *dom = [c.domain hasPrefix:@"."] ? [c.domain substringFromIndex:1] : c.domain;
                    if ([dom.lowercaseString isEqualToString:@"linux.do"] && [targetNames containsObject:c.name]) {
                        dispatch_group_enter(delGroup);
                        [store deleteCookie:c completionHandler:^{ dispatch_group_leave(delGroup); }];
                    }
                }
                dispatch_group_notify(delGroup, dispatch_get_main_queue(), ^{
                    // 2. Insert canonical host-only Discourse session cookies (`_t`, `_forum_session`)
                    dispatch_group_t setGroup = dispatch_group_create();
                    for (NSDictionary *item in incoming) {
                        NSString *name = item[@"name"];
                        NSString *val = item[@"value"];
                        if (![name isKindOfClass:NSString.class] || ![val isKindOfClass:NSString.class] || !val.length) continue;
                        if (isCloudflareDomainCookieName(name)) continue;
                        BOOL persistent = [name isEqualToString:@"_t"];
                        NSHTTPCookie *cookie = makeHostOnlyForumCookie(name, val, persistent);
                        if (cookie) {
                            dispatch_group_enter(setGroup);
                            [store setCookie:cookie completionHandler:^{ dispatch_group_leave(setGroup); }];
                        }
                    }
                    dispatch_group_notify(setGroup, dispatch_get_main_queue(), ^{
                        purgeSharedStorageAuthCookies(NO);
                        sendReply(callback, request, @{}, nil);
                    });
                });
            }];
        } else if ([method isEqualToString:@"sweepAuthCookies"]) {
            NSString *mode = [p[@"mode"] isKindOfClass:NSString.class] ? p[@"mode"] : @"dedupe";
            BOOL isClearMode = [mode isEqualToString:@"clear"];
            purgeSharedStorageAuthCookies(isClearMode);
            NSString *preferredT = [p[@"preferredT"] isKindOfClass:NSString.class] ? p[@"preferredT"] : @"";
            NSSet<NSString *> *authNames = [NSSet setWithObjects:@"_t", @"_forum_session", @"authentication_data", nil];
            [store getAllCookies:^(NSArray<NSHTTPCookie *> *cookies) {
                if (isClearMode) {
                    dispatch_group_t grp = dispatch_group_create();
                    for (NSHTTPCookie *c in cookies) {
                        NSString *dom = [c.domain hasPrefix:@"."] ? [c.domain substringFromIndex:1] : c.domain;
                        if ([dom.lowercaseString isEqualToString:@"linux.do"] && [authNames containsObject:c.name]) {
                            dispatch_group_enter(grp);
                            [store deleteCookie:c completionHandler:^{ dispatch_group_leave(grp); }];
                        }
                    }
                    dispatch_group_notify(grp, dispatch_get_main_queue(), ^{
                        purgeSharedStorageAuthCookies(YES);
                        sendReply(callback, request, @{@"cleared": @YES}, nil);
                    });
                    return;
                }

                // mode == "dedupe":
                // Strictly deduplicate Discourse `_t` / `_forum_session` to a single host-only (`linux.do`) entry when needed.
                // Never delete, rewrite, or synthesize `cf_clearance` / `__cf_bm` / `_cfuvid` in WKHTTPCookieStore!
                NSMutableArray<NSHTTPCookie *> *tVariants = [NSMutableArray new];
                NSMutableArray<NSHTTPCookie *> *fsVariants = [NSMutableArray new];
                for (NSHTTPCookie *c in cookies) {
                    NSString *dom = [c.domain hasPrefix:@"."] ? [c.domain substringFromIndex:1] : c.domain;
                    if ([dom.lowercaseString isEqualToString:@"linux.do"]) {
                        if ([c.name isEqualToString:@"_t"] && c.value.length > 0) [tVariants addObject:c];
                        else if ([c.name isEqualToString:@"_forum_session"] && c.value.length > 0) [fsVariants addObject:c];
                    }
                }
                BOOL tNeedsRewrite = tVariants.count > 1 || (tVariants.count == 1 && [tVariants.firstObject.domain hasPrefix:@"."]);
                BOOL fsNeedsRewrite = fsVariants.count > 1 || (fsVariants.count == 1 && [fsVariants.firstObject.domain hasPrefix:@"."]);
                if (!tNeedsRewrite && !fsNeedsRewrite) {
                    sendReply(callback, request, @{@"swept": @NO}, nil);
                    return;
                }

                NSString *winnerT = nil;
                if (tNeedsRewrite && tVariants.count > 0) {
                    for (NSHTTPCookie *c in tVariants) {
                        if (preferredT.length > 0 && [c.value isEqualToString:preferredT]) { winnerT = c.value; break; }
                    }
                    if (!winnerT) {
                        for (NSHTTPCookie *c in [tVariants reverseObjectEnumerator]) {
                            if (![c.domain hasPrefix:@"."]) { winnerT = c.value; break; }
                        }
                    }
                    if (!winnerT) winnerT = tVariants.lastObject.value;
                }
                NSString *winnerFs = nil;
                if (fsNeedsRewrite && fsVariants.count > 0) {
                    for (NSHTTPCookie *c in [fsVariants reverseObjectEnumerator]) {
                        if (![c.domain hasPrefix:@"."]) { winnerFs = c.value; break; }
                    }
                    if (!winnerFs) winnerFs = fsVariants.lastObject.value;
                }

                NSMutableArray<NSHTTPCookie *> *toDelete = [NSMutableArray new];
                if (tNeedsRewrite) [toDelete addObjectsFromArray:tVariants];
                if (fsNeedsRewrite) [toDelete addObjectsFromArray:fsVariants];

                dispatch_group_t delGrp = dispatch_group_create();
                for (NSHTTPCookie *c in toDelete) {
                    dispatch_group_enter(delGrp);
                    [store deleteCookie:c completionHandler:^{ dispatch_group_leave(delGrp); }];
                }
                dispatch_group_notify(delGrp, dispatch_get_main_queue(), ^{
                    dispatch_group_t writeGrp = dispatch_group_create();
                    if (tNeedsRewrite && winnerT.length > 0) {
                        NSHTTPCookie *tc = makeHostOnlyForumCookie(@"_t", winnerT, YES);
                        if (tc) {
                            dispatch_group_enter(writeGrp);
                            [store setCookie:tc completionHandler:^{ dispatch_group_leave(writeGrp); }];
                        }
                    }
                    if (fsNeedsRewrite && winnerFs.length > 0) {
                        NSHTTPCookie *fc = makeHostOnlyForumCookie(@"_forum_session", winnerFs, NO);
                        if (fc) {
                            dispatch_group_enter(writeGrp);
                            [store setCookie:fc completionHandler:^{ dispatch_group_leave(writeGrp); }];
                        }
                    }
                    dispatch_group_notify(writeGrp, dispatch_get_main_queue(), ^{
                        purgeSharedStorageAuthCookies(NO);
                        sendReply(callback, request, @{@"swept": @YES}, nil);
                    });
                });
            }];
        } else if ([method isEqualToString:@"clearCookies"] || [method isEqualToString:@"deleteCookie"]) {
            [store getAllCookies:^(NSArray<NSHTTPCookie *> *cookies) {
                dispatch_group_t group = dispatch_group_create();
                for (NSHTTPCookie *cookie in cookies) {
                    NSString *domain = [cookie.domain hasPrefix:@"."] ? [cookie.domain substringFromIndex:1] : cookie.domain;
                    BOOL matches = [method isEqualToString:@"clearCookies"] ||
                        ([cookie.name isEqualToString:p[@"name"]] && [cookie.domain isEqualToString:p[@"domain"]] && [cookie.path isEqualToString:p[@"path"]]);
                    if (isForumHost(domain) && matches) {
                        dispatch_group_enter(group);
                        [store deleteCookie:cookie completionHandler:^{ dispatch_group_leave(group); }];
                    }
                }
                dispatch_group_notify(group, dispatch_get_main_queue(), ^{
                    sendReply(callback, request, @{}, nil);
                });
            }];
        } else if ([method isEqualToString:@"rsaGenerate"]) {
            NSDictionary *attrs = @{
                (id)kSecAttrKeyType: (id)kSecAttrKeyTypeRSA,
                (id)kSecAttrKeySizeInBits: @2048
            };
            CFErrorRef err = NULL;
            SecKeyRef privKey = SecKeyCreateRandomKey((__bridge CFDictionaryRef)attrs, &err);
            if (!privKey) {
                NSString *msg = err ? [(__bridge NSError *)err localizedDescription] : @"RSA keygen failed";
                if (err) CFRelease(err);
                sendReply(callback, request, nil, msg);
                return;
            }
            SecKeyRef pubKey = SecKeyCopyPublicKey(privKey);
            NSData *privData = (__bridge_transfer NSData *)SecKeyCopyExternalRepresentation(privKey, &err);
            NSData *pubPkcs1 = pubKey ? (__bridge_transfer NSData *)SecKeyCopyExternalRepresentation(pubKey, &err) : nil;
            if (pubKey) CFRelease(pubKey);
            CFRelease(privKey);
            if (!privData || !pubPkcs1) {
                if (err) CFRelease(err);
                sendReply(callback, request, nil, @"RSA key export failed");
                return;
            }
            NSData *spki = wrapPkcs1PublicKeyInSpki(pubPkcs1);
            NSString *b64Pub = [spki base64EncodedStringWithOptions:NSDataBase64Encoding64CharacterLineLength | NSDataBase64EncodingEndLineWithLineFeed];
            NSString *pem = [NSString stringWithFormat:@"-----BEGIN PUBLIC KEY-----\n%@\n-----END PUBLIC KEY-----", b64Pub];
            NSString *privB64 = [privData base64EncodedStringWithOptions:0];
            sendReply(callback, request, @{@"publicPem": pem, @"privateBase64": privB64}, nil);
        } else if ([method isEqualToString:@"rsaDecrypt"]) {
            NSString *privB64 = p[@"privateBase64"] ?: @"";
            NSString *cipherB64 = p[@"cipherBase64"] ?: @"";
            NSData *privData = [[NSData alloc] initWithBase64EncodedString:privB64 options:NSDataBase64DecodingIgnoreUnknownCharacters];
            NSData *cipherData = [[NSData alloc] initWithBase64EncodedString:cipherB64 options:NSDataBase64DecodingIgnoreUnknownCharacters];
            if (!privData.length || !cipherData.length) {
                sendReply(callback, request, nil, @"Invalid RSA base64 input");
                return;
            }
            NSDictionary *attrs = @{
                (id)kSecAttrKeyType: (id)kSecAttrKeyTypeRSA,
                (id)kSecAttrKeyClass: (id)kSecAttrKeyClassPrivate,
                (id)kSecAttrKeySizeInBits: @2048
            };
            CFErrorRef err = NULL;
            SecKeyRef privKey = SecKeyCreateWithData((__bridge CFDataRef)privData, (__bridge CFDictionaryRef)attrs, &err);
            if (!privKey) {
                if (err) CFRelease(err);
                sendReply(callback, request, nil, @"Invalid RSA private key");
                return;
            }
            NSData *plain = (__bridge_transfer NSData *)SecKeyCreateDecryptedData(
                privKey,
                kSecKeyAlgorithmRSAEncryptionPKCS1,
                (__bridge CFDataRef)cipherData,
                &err
            );
            CFRelease(privKey);
            if (!plain) {
                if (err) CFRelease(err);
                sendReply(callback, request, nil, @"RSA decrypt failed");
                return;
            }
            NSString *text = [[NSString alloc] initWithData:plain encoding:NSUTF8StringEncoding] ?: @"";
            sendReply(callback, request, @{@"plain": text}, nil);
        } else if ([method isEqualToString:@"fetchConnectHtml"]) {
            LindoConnectProbe *probe = [[LindoConnectProbe alloc] initWithCallback:callback reqId:request];
            [probe start];
        } else {
            sendReply(callback, request, nil, @"Unknown native bridge command");
        }
    });
}
