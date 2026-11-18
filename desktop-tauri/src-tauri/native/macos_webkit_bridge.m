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

@class LindoBridgeHost;

@interface LindoCfScriptHandler : NSObject <WKScriptMessageHandler>
@property (weak) LindoBridgeHost *host;
@end

@interface LindoBridgeHost : NSObject <WKNavigationDelegate, WKUIDelegate, NSWindowDelegate>
@property WKWebView *web;
@property NSPanel *panel;
@property NSView *panelRoot;
@property NSView *headerBar;
@property NSView *webClipView;
@property NSImageView *shieldIconView;
@property NSTextField *headerTitleLabel;
@property NSButton *reloadBtn;
@property NSButton *closeBtn;
@property LindoVerifyMaskView *maskView;
@property NSProgressIndicator *maskSpinner;
@property NSTextField *maskLabel;
@property BOOL maskVisibleState;
@property BOOL maskDarkState;
@property NSString *maskMessageState;
@property BOOL loginModeState;
@property BOOL challengeOriginReached;
@property BOOL challengeInteractiveRequired;
@property NSProgressIndicator *progress;
@property NSButton *browserAuthBtn;
@property BOOL cancelled;
@property BOOL processTerminated;
@property NSString *navigationError;
@property id<NSObject> activityToken;
- (void)handleCfScriptEvent:(NSString *)eventName;
- (void)alignTurnstileInClipView;
@end

@implementation LindoCfScriptHandler
- (void)userContentController:(WKUserContentController *)userContentController didReceiveScriptMessage:(WKScriptMessage *)message {
    if ([message.body isKindOfClass:NSString.class]) {
        [self.host handleCfScriptEvent:(NSString *)message.body];
    }
}
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

        WKUserContentController *ucc = [WKUserContentController new];
        LindoCfScriptHandler *cfHandler = [LindoCfScriptHandler new];
        cfHandler.host = self;
        [ucc addScriptMessageHandler:cfHandler name:@"lindoCf"];

        // Passive 0-frame Turnstile interactiveBegin & beforeunload observer
        NSString *cfLifecycleJs =
            @"(function(){"
             "  function post(ev){try{window.webkit.messageHandlers.lindoCf.postMessage(ev);}catch(_){}}"
             "  window.addEventListener('message',function(e){"
             "    try{"
             "      var d=e&&e.data;"
             "      if(typeof d==='string'&&d.charAt(0)==='{'){d=JSON.parse(d);}"
             "      if(d&&d.source==='cloudflare-challenge'&&(d.event==='interactiveBegin'||d.event==='interactiveTimeout')){"
             "        post('interactive');"
             "      }"
             "    }catch(_){}"
             "  },false);"
             "  function onLeave(){"
             "    if(location.origin==='https://linux.do'&&location.pathname.indexOf('/challenge')===0){"
             "      post('unloading');"
             "    }"
             "  }"
             "  window.addEventListener('beforeunload',onLeave,true);"
             "  window.addEventListener('pagehide',onLeave,true);"
             "})();";
        WKUserScript *lifecycleScript = [[WKUserScript alloc] initWithSource:cfLifecycleJs
                                                               injectionTime:WKUserScriptInjectionTimeAtDocumentStart
                                                            forMainFrameOnly:YES];
        [ucc addUserScript:lifecycleScript];

        // Isolated client-world surface styling ONLY on `/challenge` so surrounding boilerplate never clutters the compact card,
        // while keeping `.main-wrapper`, `#turnstile-wrapper`, `.cf-turnstile`, and `iframe` 100% untouched in natural viewport!
        NSString *challengeStageCleanJs =
            @"(function(){"
             "  if(location.origin!=='https://linux.do'||location.pathname.indexOf('/challenge')!==0)return;"
             "  function inject(){"
             "    var root=document.head||document.documentElement;"
             "    if(!root||document.getElementById('__lindo_cf_clean'))return;"
             "    var st=document.createElement('style');"
             "    st.id='__lindo_cf_clean';"
             "    st.textContent='html,body{margin:0!important;padding:0!important;overflow:hidden!important;background:transparent!important;}'"
             "      +'.zone-name-title,.h2,#challenge-body-text,#challenge-explainer-expandable,.footer,.ray-id,.clearfix.footer{display:none!important;}'"
             "      +'.main-wrapper{margin:0 auto!important;padding:18px 0 0 0!important;min-height:0!important;display:flex!important;justify-content:center!important;}'"
             "      +'.main-content{margin:0 auto!important;padding:0!important;width:auto!important;display:flex!important;flex-direction:column!important;align-items:center!important;}'"
             "      +'#turnstile-wrapper,.cf-turnstile{margin:0 auto!important;}';"
             "    root.appendChild(st);"
             "  }"
             "  inject();"
             "  document.addEventListener('DOMContentLoaded',inject,{once:true});"
             "})();";
        WKUserScript *cleanStageScript = [[WKUserScript alloc] initWithSource:challengeStageCleanJs
                                                                injectionTime:WKUserScriptInjectionTimeAtDocumentStart
                                                             forMainFrameOnly:YES
                                                               inContentWorld:WKContentWorld.defaultClientWorld];
        [ucc addUserScript:cleanStageScript];
        config.userContentController = ucc;

        // Keep `_web` 100% unparented (offscreen, no NSWindow/CGSWindow) until `show:loginMode:` actually displays a sheet!
        _web = [[WKWebView alloc] initWithFrame:NSMakeRect(0, 0, 420, 480) configuration:config];
        if ([_web respondsToSelector:@selector(setDrawsBackground:)]) {
            [_web setValue:@NO forKey:@"drawsBackground"];
        }
        _web.navigationDelegate = self;
        _web.UIDelegate = self;
        _maskVisibleState = NO;
        _maskDarkState = YES;
        _maskMessageState = @"校验连接中…";
        _loginModeState = NO;
        _challengeOriginReached = NO;
        _challengeInteractiveRequired = NO;
    }
    return self;
}

- (void)handleCfScriptEvent:(NSString *)eventName {
    if ([eventName isEqualToString:@"interactive"]) {
        self.challengeInteractiveRequired = YES;
        if (!self.loginModeState && _panel && _panel.isVisible && !self.challengeOriginReached) {
            [self setVerifyMask:NO dark:self.maskDarkState message:@""];
            [self alignTurnstileInClipView];
        }
    } else if ([eventName isEqualToString:@"unloading"]) {
        if (!self.loginModeState) {
            // Instant 0ms mask cover BEFORE the post-challenge 404 navigation even starts!
            [self setVerifyMask:YES dark:self.maskDarkState message:@"已通过，正在继续…"];
        }
    }
}

- (void)alignTurnstileInClipView {
    if (self.loginModeState || !_webClipView || _web.superview != _webClipView) return;
    CGFloat clipW = _webClipView.bounds.size.width;
    CGFloat clipH = _webClipView.bounds.size.height;
    CGFloat webW = 420.0;
    CGFloat webH = 480.0;
    NSString *rectJs =
        @"return (() => {"
         "  const el = document.querySelector('iframe[src*=\"challenges.cloudflare.com\"], #turnstile-wrapper, .cf-turnstile, #challenge-stage');"
         "  if (!el) return null;"
         "  const r = el.getBoundingClientRect();"
         "  if (!r || r.width < 40 || r.height < 20) return null;"
         "  return { cx: r.left + r.width * 0.5, cy: r.top + r.height * 0.5 };"
         "})();";
    [_web callAsyncJavaScript:rectJs
                    arguments:@{}
                      inFrame:nil
               inContentWorld:WKContentWorld.defaultClientWorld
            completionHandler:^(id result, NSError *error) {
        if (error || self.loginModeState || self.web.superview != self.webClipView) return;
        if ([result isKindOfClass:NSDictionary.class]) {
            NSDictionary *d = (NSDictionary *)result;
            CGFloat cx = [d[@"cx"] doubleValue];
            CGFloat cy = [d[@"cy"] doubleValue];
            if (cx > 10.0 && cy > 5.0) {
                CGFloat ox = round(clipW * 0.5 - cx);
                CGFloat oy = round(clipH * 0.5 + cy - webH);
                [self.web setFrame:NSMakeRect(ox, oy, webW, webH)];
            }
        }
    }];
}

- (void)layoutCardForMode:(BOOL)loginMode dark:(BOOL)dark owner:(NSWindow *)owner {
    CGFloat cardW = loginMode ? 540.0 : 384.0;
    CGFloat cardH = loginMode ? 620.0 : 178.0;
    if (loginMode && owner) {
        cardW = MAX(440.0, MIN(580.0, owner.frame.size.width - 64.0));
        cardH = MAX(480.0, MIN(640.0, owner.frame.size.height - 72.0));
    }
    CGFloat headerH = 40.0;
    CGFloat bodyH = MAX(96.0, cardH - headerH);

    _panel.minSize = loginMode ? NSMakeSize(420, 460) : NSMakeSize(cardW, cardH);
    _panel.maxSize = loginMode ? NSMakeSize(900, 900) : NSMakeSize(cardW, cardH);
    [_panel setContentSize:NSMakeSize(cardW, cardH)];

    NSColor *cardBg = dark
        ? [NSColor colorWithRed:0.094 green:0.114 blue:0.153 alpha:1.0] // #181D27
        : [NSColor colorWithRed:0.976 green:0.980 blue:0.988 alpha:1.0]; // #F9FAFC
    NSColor *titleFg = dark
        ? [NSColor colorWithRed:0.933 green:0.945 blue:0.969 alpha:1.0]
        : [NSColor colorWithRed:0.090 green:0.110 blue:0.149 alpha:1.0];
    NSColor *mutedFg = dark
        ? [NSColor colorWithRed:0.545 green:0.584 blue:0.659 alpha:1.0]
        : [NSColor colorWithRed:0.420 green:0.455 blue:0.522 alpha:1.0];

    _panelRoot.frame = NSMakeRect(0, 0, cardW, cardH);
    _panelRoot.layer.backgroundColor = cardBg.CGColor;

    // Seamless borderless header (ZERO horizontal divider line between header & content!)
    _headerBar.frame = NSMakeRect(0, bodyH, cardW, headerH);
    _shieldIconView.contentTintColor = dark
        ? [NSColor colorWithRed:0.835 green:0.863 blue:0.910 alpha:1.0]
        : [NSColor colorWithRed:0.250 green:0.290 blue:0.360 alpha:1.0];
    _shieldIconView.frame = NSMakeRect(15, 12, 15, 15);

    _headerTitleLabel.stringValue = loginMode ? @"登录 LINUX DO" : @"安全确认";
    _headerTitleLabel.textColor = titleFg;
    _headerTitleLabel.frame = NSMakeRect(36, 11, loginMode ? 180 : 240, 18);

    _closeBtn.contentTintColor = mutedFg;
    _closeBtn.frame = NSMakeRect(cardW - 34, 8, 24, 24);
    _reloadBtn.contentTintColor = mutedFg;
    _reloadBtn.frame = NSMakeRect(cardW - 60, 8, 24, 24);
    _progress.frame = NSMakeRect(cardW - 82, 12, 16, 16);

    _browserAuthBtn.hidden = !loginMode;
    if (loginMode) {
        _browserAuthBtn.frame = NSMakeRect(cardW - 246, 6, 160, 28);
    }

    _webClipView.frame = NSMakeRect(0, 0, cardW, bodyH);
    _maskView.frame = NSMakeRect(0, 0, cardW, bodyH);
    _maskSpinner.frame = NSMakeRect(round((cardW - 18.0) * 0.5), round(bodyH * 0.5 + 6.0), 18, 18);
    _maskLabel.frame = NSMakeRect(16, round(bodyH * 0.5 - 20.0), cardW - 32.0, 18);

    if (_web.superview != _webClipView) {
        [_webClipView addSubview:_web];
    }
    if (loginMode) {
        _web.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
        [_web setFrame:NSMakeRect(0, 0, cardW, bodyH)];
    } else {
        _web.autoresizingMask = NSViewNotSizable;
        // Default centered frame for standard 300x65 Turnstile inside 420x480 WebView before exact rect alignment
        [_web setFrame:NSMakeRect(round((cardW - 420.0) * 0.5), bodyH - 480.0 - 2.0, 420.0, 480.0)];
        [self alignTurnstileInClipView];
    }
}

- (void)ensurePanelCreated {
    if (_panel) return;
    _panel = [[LindoBridgePanel alloc]
        initWithContentRect:NSMakeRect(0, 0, 384, 178)
                  styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable | NSWindowStyleMaskFullSizeContentView
                    backing:NSBackingStoreBuffered
                      defer:YES];
    _panel.title = @"安全确认";
    _panel.titleVisibility = NSWindowTitleHidden;
    _panel.titlebarAppearsTransparent = YES;
    _panel.movableByWindowBackground = YES;
    _panel.releasedWhenClosed = NO;
    _panel.delegate = self;
    _panel.hidesOnDeactivate = NO;
    [_panel standardWindowButton:NSWindowCloseButton].hidden = YES;
    [_panel standardWindowButton:NSWindowMiniaturizeButton].hidden = YES;
    [_panel standardWindowButton:NSWindowZoomButton].hidden = YES;

    NSView *root = [[NSView alloc] initWithFrame:NSMakeRect(0, 0, 384, 178)];
    root.wantsLayer = YES;
    root.layer.backgroundColor = [NSColor colorWithRed:0.094 green:0.114 blue:0.153 alpha:1.0].CGColor;
    _panelRoot = root;

    _webClipView = [[NSView alloc] initWithFrame:NSMakeRect(0, 0, 384, 138)];
    _webClipView.wantsLayer = YES;
    _webClipView.layer.masksToBounds = YES;
    _webClipView.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    [root addSubview:_webClipView];

    _maskView = [[LindoVerifyMaskView alloc] initWithFrame:NSMakeRect(0, 0, 384, 138)];
    _maskView.wantsLayer = YES;
    _maskView.layer.backgroundColor = [NSColor colorWithRed:0.094 green:0.114 blue:0.153 alpha:1.0].CGColor;
    _maskView.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    _maskView.hidden = YES;

    _maskSpinner = [[NSProgressIndicator alloc] initWithFrame:NSMakeRect(183, 75, 18, 18)];
    _maskSpinner.style = NSProgressIndicatorStyleSpinning;
    _maskSpinner.controlSize = NSControlSizeSmall;
    _maskSpinner.displayedWhenStopped = NO;
    [_maskView addSubview:_maskSpinner];

    _maskLabel = [NSTextField labelWithString:@"校验连接中…"];
    _maskLabel.textColor = NSColor.secondaryLabelColor;
    _maskLabel.font = [NSFont systemFontOfSize:12.2 weight:NSFontWeightMedium];
    _maskLabel.alignment = NSTextAlignmentCenter;
    _maskLabel.frame = NSMakeRect(16, 48, 352, 18);
    [_maskView addSubview:_maskLabel];

    [root addSubview:_maskView];

    // Borderless top header bar (zero hairline between header & content!)
    NSView *bar = [[NSView alloc] initWithFrame:NSMakeRect(0, 138, 384, 40)];
    bar.autoresizingMask = NSViewWidthSizable | NSViewMinYMargin;
    _headerBar = bar;

    NSImage *shieldImg = [NSImage imageWithSystemSymbolName:@"checkmark.shield" accessibilityDescription:@"安全确认"];
    if (!shieldImg) {
        shieldImg = [NSImage imageWithSystemSymbolName:@"lock.shield" accessibilityDescription:@"安全确认"];
    }
    _shieldIconView = [NSImageView imageViewWithImage:shieldImg];
    _shieldIconView.imageScaling = NSImageScaleProportionallyUpOrDown;
    [bar addSubview:_shieldIconView];

    _headerTitleLabel = [NSTextField labelWithString:@"安全确认"];
    _headerTitleLabel.font = [NSFont systemFontOfSize:13 weight:NSFontWeightSemibold];
    _headerTitleLabel.alignment = NSTextAlignmentLeft;
    [bar addSubview:_headerTitleLabel];

    _browserAuthBtn = [NSButton buttonWithTitle:@"切换默认浏览器一键授权 ↗"
                                         target:self
                                         action:@selector(switchToBrowserAuth:)];
    _browserAuthBtn.bordered = NO;
    _browserAuthBtn.font = [NSFont systemFontOfSize:11.5 weight:NSFontWeightMedium];
    _browserAuthBtn.contentTintColor = [NSColor colorWithRed:0.988 green:0.827 blue:0.302 alpha:1.0];
    _browserAuthBtn.hidden = YES;
    [bar addSubview:_browserAuthBtn];

    _progress = [[NSProgressIndicator alloc] initWithFrame:NSMakeRect(302, 12, 16, 16)];
    _progress.style = NSProgressIndicatorStyleSpinning;
    _progress.controlSize = NSControlSizeSmall;
    _progress.displayedWhenStopped = NO;
    [bar addSubview:_progress];

    NSImageSymbolConfiguration *btnSymCfg = [NSImageSymbolConfiguration configurationWithPointSize:11.5 weight:NSFontWeightMedium];
    NSImage *reloadImg = [[NSImage imageWithSystemSymbolName:@"arrow.clockwise" accessibilityDescription:@"重新加载"] imageWithSymbolConfiguration:btnSymCfg];
    _reloadBtn = [NSButton buttonWithImage:reloadImg target:self action:@selector(reloadPage:)];
    _reloadBtn.bordered = NO;
    [bar addSubview:_reloadBtn];

    NSImage *closeImg = [[NSImage imageWithSystemSymbolName:@"xmark" accessibilityDescription:@"关闭"] imageWithSymbolConfiguration:btnSymCfg];
    _closeBtn = [NSButton buttonWithImage:closeImg target:self action:@selector(closePanel:)];
    _closeBtn.bordered = NO;
    [bar addSubview:_closeBtn];

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

- (void)reloadPage:(id)sender {
    if (!self.loginModeState) {
        self.challengeOriginReached = NO;
        [self setVerifyMask:YES dark:self.maskDarkState message:@"校验连接中…"];
    }
    [_web reload];
}
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
        ? [NSColor colorWithRed:0.094 green:0.114 blue:0.153 alpha:1.0]
        : [NSColor colorWithRed:0.976 green:0.980 blue:0.988 alpha:1.0];
    _maskView.layer.backgroundColor = bg.CGColor;
    _maskLabel.textColor = _maskDarkState
        ? [NSColor colorWithRed:0.640 green:0.675 blue:0.740 alpha:1.0]
        : [NSColor colorWithRed:0.322 green:0.345 blue:0.400 alpha:1.0];
    if (_maskMessageState.length > 0) {
        _maskLabel.stringValue = _maskMessageState;
    }
    if (_maskVisibleState) {
        _maskView.hidden = NO;
        _web.alphaValue = 0.0;
        if (_panel && _panel.isVisible) {
            [_maskSpinner startAnimation:nil];
        }
    } else {
        [_maskSpinner stopAnimation:nil];
        _web.alphaValue = 1.0;
        _maskView.hidden = YES;
        [self alignTurnstileInClipView];
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
    _loginModeState = NO;
    _web.alphaValue = 1.0;
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
    self.loginModeState = loginMode;
    self.maskDarkState = dark;
    [self ensurePanelCreated];
    _panel.appearance = [NSAppearance appearanceNamed:dark ? NSAppearanceNameDarkAqua : NSAppearanceNameAqua];

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

    [self layoutCardForMode:loginMode dark:dark owner:owner];
    [self applyMaskVisuals];
    [NSApp activateIgnoringOtherApps:YES];

    if (_panel.isVisible) {
        [_panel makeKeyAndOrderFront:nil];
        [_panel makeFirstResponder:_web];
        if (_maskVisibleState) [_maskSpinner startAnimation:nil];
        return;
    }
    if (owner && owner != _panel && !owner.attachedSheet) {
        owner.appearance = _panel.appearance;
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
        if (!self.loginModeState) {
            [self alignTurnstileInClipView];
        }
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(450 * NSEC_PER_MSEC)), dispatch_get_main_queue(), ^{
            if (!self.panel || !self.panel.isVisible) return;
            if (!self.loginModeState) {
                [self alignTurnstileInClipView];
            }
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
    if (_panel && _panel.isVisible && !self.loginModeState) {
        [self alignTurnstileInClipView];
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
    // If we already displayed Turnstile and main frame is navigating back to bare `/challenge`, cover immediately before request!
    if (allowed && !self.loginModeState && self.challengeInteractiveRequired && [url.path isEqualToString:@"/challenge"] && !url.query.length) {
        [self setVerifyMask:YES dark:self.maskDarkState message:@"已通过，正在继续…"];
    }
    decision(allowed ? WKNavigationActionPolicyAllow : WKNavigationActionPolicyCancel);
}

- (void)webView:(WKWebView *)webView decidePolicyForNavigationResponse:(WKNavigationResponse *)navigationResponse decisionHandler:(void (^)(WKNavigationResponsePolicy))decisionHandler {
    if (navigationResponse.isForMainFrame && [navigationResponse.response isKindOfClass:NSHTTPURLResponse.class]) {
        NSHTTPURLResponse *http = (NSHTTPURLResponse *)navigationResponse.response;
        NSString *host = http.URL.host.lowercaseString ?: @"";
        NSString *path = http.URL.path ?: @"";
        NSString *cfRaw = nil;
        for (NSString *k in http.allHeaderFields) {
            if ([k.lowercaseString isEqualToString:@"cf-mitigated"]) {
                id v = http.allHeaderFields[k];
                if ([v isKindOfClass:NSString.class]) cfRaw = [(NSString *)v lowercaseString];
                break;
            }
        }
        BOOL isCfChallenge = (cfRaw != nil && [cfRaw rangeOfString:@"challenge"].location != NSNotFound);
        // Synchronous 0-frame interception BEFORE WebKit commits or paints 1 pixel of Discourse's 404/200/500 HTML:
        if (!self.loginModeState && isForumHost(host) && !isCfChallenge) {
            if ([path hasPrefix:@"/challenge"] && (http.statusCode == 404 || (http.statusCode >= 200 && http.statusCode < 400))) {
                self.challengeOriginReached = YES;
                [self setVerifyMask:YES dark:self.maskDarkState message:@"已通过，正在继续…"];
            } else if (http.statusCode == 200 || http.statusCode == 404 || http.statusCode >= 500) {
                [self setVerifyMask:YES dark:self.maskDarkState message:@"已通过，正在继续…"];
            }
        }
    }
    decisionHandler(WKNavigationResponsePolicyAllow);
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
            NSString *msg = [p[@"message"] isKindOfClass:NSString.class] ? p[@"message"] : @"校验连接中…";
            [gHost setVerifyMask:visible dark:dark message:msg];
            sendReply(callback, request, @{}, nil);
        } else if ([method isEqualToString:@"status"]) {
            sendReply(callback, request, @{
                @"cancelled": @(gHost.cancelled),
                @"loading": @(gHost.web.loading),
                @"processTerminated": @(gHost.processTerminated),
                @"origin404": @(gHost.challengeOriginReached),
                @"interactiveRequired": @(gHost.challengeInteractiveRequired),
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
            gHost.challengeOriginReached = NO;
            gHost.challengeInteractiveRequired = NO;
            if ([url.path hasPrefix:@"/login"] || [url.path hasPrefix:@"/session/otp/"]) {
                gHost.loginModeState = YES;
            } else if ([url.path hasPrefix:@"/challenge"]) {
                gHost.loginModeState = NO;
            }
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
