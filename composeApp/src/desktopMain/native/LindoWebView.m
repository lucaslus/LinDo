#import <Cocoa/Cocoa.h>
#import <WebKit/WebKit.h>

typedef void (*LindoReply)(long long, const char *);
static LindoReply eventReply;
static BOOL forumHost(NSString *host) {
    return [host isEqualToString:@"linux.do"] || [host hasSuffix:@".linux.do"];
}
static void reply(LindoReply callback, long long request, id value, NSString *error) {
    NSDictionary *result = error ? @{@"error":error} : @{@"value":value ?: [NSNull null]};
    // A non-JSON JavaScript result must still complete the Kotlin continuation.
    if (![NSJSONSerialization isValidJSONObject:result]) result = @{@"error":@"Unsupported web result"};
    NSData *data = [NSJSONSerialization dataWithJSONObject:result options:0 error:nil];
    if (callback && data) callback(request, [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding].UTF8String);
}

@interface LindoVerifyPanel : NSPanel
@end
@implementation LindoVerifyPanel
- (BOOL)canBecomeKeyWindow { return YES; }
- (void)cancelOperation:(id)sender {
    if ([self.delegate respondsToSelector:@selector(windowShouldClose:)]) {
        [self.delegate windowShouldClose:self];
    }
}
- (BOOL)performKeyEquivalent:(NSEvent *)event {
    // Java AWT's menu bar omits the standard macOS Edit menu, so route standard editing shortcuts
    // through the responder chain to WKWebView (paste:, copy:, cut:, selectAll:, undo:, redo:).
    NSEventModifierFlags flags = event.modifierFlags & NSEventModifierFlagDeviceIndependentFlagsMask;
    NSString *key = event.charactersIgnoringModifiers.lowercaseString;
    if (flags == NSEventModifierFlagCommand) {
        if ([key isEqualToString:@"v"]) return [NSApp sendAction:@selector(paste:) to:nil from:self];
        if ([key isEqualToString:@"c"]) return [NSApp sendAction:@selector(copy:) to:nil from:self];
        if ([key isEqualToString:@"x"]) return [NSApp sendAction:@selector(cut:) to:nil from:self];
        if ([key isEqualToString:@"a"]) return [NSApp sendAction:@selector(selectAll:) to:nil from:self];
        if ([key isEqualToString:@"z"]) return [NSApp sendAction:NSSelectorFromString(@"undo:") to:nil from:self];
    } else if (flags == (NSEventModifierFlagCommand | NSEventModifierFlagShift)) {
        if ([key isEqualToString:@"z"]) return [NSApp sendAction:NSSelectorFromString(@"redo:") to:nil from:self];
    }
    return [super performKeyEquivalent:event];
}
@end

@interface LindoWebHost : NSObject <WKNavigationDelegate, WKUIDelegate, NSWindowDelegate>
@property WKWebView *web;
@property NSPanel *panel;
@property NSProgressIndicator *progress;
@property NSTextField *originLabel;
@property BOOL cancelled;
@property NSString *navigationError;
@end
@implementation LindoWebHost
- (instancetype)init { return [self initWithDataStore:WKWebsiteDataStore.defaultDataStore]; }
- (instancetype)initWithDataStore:(WKWebsiteDataStore *)store {
    self = [super init];
    if (self) {
        WKWebViewConfiguration *config = [WKWebViewConfiguration new];
        config.websiteDataStore = store;
        _web = [[WKWebView alloc] initWithFrame:NSMakeRect(0,0,680,680) configuration:config];
        _web.navigationDelegate = self;
        _web.UIDelegate = self;
        _web.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
        _panel = [[LindoVerifyPanel alloc] initWithContentRect:NSMakeRect(0,0,680,720)
            styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable | NSWindowStyleMaskResizable
            backing:NSBackingStoreBuffered defer:NO];
        _panel.title = @"linux.do";
        _panel.releasedWhenClosed = NO;
        _panel.delegate = self;
        _panel.hidesOnDeactivate = NO;
        _panel.minSize = NSMakeSize(380,400);
        NSView *root = [[NSView alloc] initWithFrame:NSMakeRect(0,0,680,560)];
        root.wantsLayer = YES;
        root.layer.backgroundColor = [NSColor colorWithRed:0.06 green:0.07 blue:0.085 alpha:1].CGColor;
        _web.frame = NSMakeRect(0,0,680,524);
        [root addSubview:_web];
        NSView *bar = [[NSView alloc] initWithFrame:NSMakeRect(0,524,680,36)];
        bar.autoresizingMask = NSViewWidthSizable | NSViewMinYMargin;
        NSButton *close = [NSButton buttonWithImage:[NSImage imageWithSystemSymbolName:@"xmark" accessibilityDescription:@"关闭验证"] target:self action:@selector(closePanel:)];
        close.bordered = NO;
        close.frame = NSMakeRect(8,4,28,28);
        [bar addSubview:close];
        _originLabel = [NSTextField labelWithString:@"linux.do"];
        _originLabel.textColor = NSColor.secondaryLabelColor;
        _originLabel.font = [NSFont systemFontOfSize:12];
        _originLabel.alignment = NSTextAlignmentCenter;
        _originLabel.frame = NSMakeRect(60,9,560,18);
        _originLabel.autoresizingMask = NSViewWidthSizable;
        [bar addSubview:_originLabel];
        _progress = [[NSProgressIndicator alloc] initWithFrame:NSMakeRect(620,9,18,18)];
        _progress.style = NSProgressIndicatorStyleSpinning;
        _progress.controlSize = NSControlSizeSmall;
        _progress.displayedWhenStopped = NO;
        _progress.autoresizingMask = NSViewMinXMargin;
        [bar addSubview:_progress];
        NSButton *reload = [NSButton buttonWithImage:[NSImage imageWithSystemSymbolName:@"arrow.clockwise" accessibilityDescription:@"重新加载"] target:self action:@selector(reload:)];
        reload.bordered = NO;
        reload.frame = NSMakeRect(644,4,28,28);
        reload.autoresizingMask = NSViewMinXMargin;
        [bar addSubview:reload];
        [root addSubview:bar];
        _panel.contentView = root;

    }
    return self;
}
- (void)reload:(id)sender { [_web reload]; }
- (void)closePanel:(id)sender { [self windowShouldClose:_panel]; }
- (void)hide {
    if (_panel.sheetParent) [_panel.sheetParent endSheet:_panel];
    [_panel orderOut:nil];
}
- (BOOL)windowShouldClose:(NSWindow *)sender {
    self.cancelled = YES;
    [self hide];
    return NO;
}
- (void)show:(BOOL)dark {
    self.cancelled = NO;
    _panel.appearance = [NSAppearance appearanceNamed:dark ? NSAppearanceNameDarkAqua : NSAppearanceNameAqua];
    NSWindow *owner = NSApp.keyWindow;
    if (!owner || owner == _panel) {
        for (NSWindow *candidate in NSApp.orderedWindows) {
            if (candidate != _panel && candidate.isVisible && ![candidate isKindOfClass:NSPanel.class]) { owner = candidate; break; }
        }
    }
    if (_panel.isVisible) { [_panel makeKeyAndOrderFront:nil]; return; }
    if (owner && owner != _panel && !owner.attachedSheet) {
        owner.appearance = _panel.appearance;
        [_panel setContentSize:NSMakeSize(MAX(380,MIN(680,owner.frame.size.width-48)),MAX(400,MIN(560,owner.frame.size.height-80)))];
        [owner beginSheet:_panel completionHandler:nil];
        [_panel makeFirstResponder:_web];
    } else {
        [_panel center];
        [_panel makeKeyAndOrderFront:nil];
        [_panel makeFirstResponder:_web];
    }
}
- (void)webView:(WKWebView *)webView didStartProvisionalNavigation:(WKNavigation *)navigation {
    self.navigationError = nil;
    [_progress startAnimation:nil];
}
- (void)webView:(WKWebView *)webView didFinishNavigation:(WKNavigation *)navigation { [_progress stopAnimation:nil]; }
- (void)webView:(WKWebView *)webView didFailProvisionalNavigation:(WKNavigation *)navigation withError:(NSError *)error {
    if (error.code == NSURLErrorCancelled) return;
    self.navigationError = @"网页暂时无法加载，请重试";
    [_progress stopAnimation:nil];
}
- (void)webView:(WKWebView *)webView didFailNavigation:(WKNavigation *)navigation withError:(NSError *)error {
    [self webView:webView didFailProvisionalNavigation:navigation withError:error];
}
- (void)webViewWebContentProcessDidTerminate:(WKWebView *)webView {
    self.navigationError = @"网页已停止响应，请重试";
    [_progress stopAnimation:nil];
}
- (void)webView:(WKWebView *)webView decidePolicyForNavigationAction:(WKNavigationAction *)action decisionHandler:(void (^)(WKNavigationActionPolicy))decision {
    // Let WebKit enforce iframe origin/CSP rules. Challenges use about:srcdoc
    // and blob frames as well as HTTPS; a top-level URL allowlist must not block them.
    if (action.targetFrame && !action.targetFrame.isMainFrame) {
        decision(WKNavigationActionPolicyAllow);
        return;
    }
    NSURL *url = action.request.URL;
    NSString *scheme = url.scheme.lowercaseString;
    if ([scheme isEqualToString:@"discourse"] || [scheme isEqualToString:@"lindo"]) {
        reply(eventReply,0,@{@"redirect":url.absoluteString},nil);
        [self hide];
        decision(WKNavigationActionPolicyCancel);
        return;
    }
    BOOL allowed = [scheme isEqualToString:@"https"] || [url.absoluteString isEqualToString:@"about:blank"];
    if (allowed && action.targetFrame.isMainFrame && url.host.length) { _panel.title = url.host; _originLabel.stringValue = url.host; }
    decision(allowed ? WKNavigationActionPolicyAllow : WKNavigationActionPolicyCancel);
}
- (WKWebView *)webView:(WKWebView *)webView createWebViewWithConfiguration:(WKWebViewConfiguration *)configuration forNavigationAction:(WKNavigationAction *)action windowFeatures:(WKWindowFeatures *)features {
    if (!action.targetFrame && [action.request.URL.scheme.lowercaseString isEqualToString:@"https"]) [webView loadRequest:action.request];
    return nil;
}
@end
static LindoWebHost *host;

// All WebKit/AppKit access is dispatched to the native main thread, never AWT's EDT.
__attribute__((visibility("default"))) void lindo_web_command(long long request, const char *raw, LindoReply callback) {
    NSString *input = raw ? [NSString stringWithUTF8String:raw] : @"{}";
    dispatch_async(dispatch_get_main_queue(), ^{
        eventReply = callback;
        if (!host) host = [LindoWebHost new];
        NSDictionary *c = [NSJSONSerialization JSONObjectWithData:[input dataUsingEncoding:NSUTF8StringEncoding] options:0 error:nil];
        NSString *method = c[@"method"];
        NSDictionary *p = c[@"params"] ?: @{};
        WKHTTPCookieStore *store = host.web.configuration.websiteDataStore.httpCookieStore;
        if ([method isEqualToString:@"init"]) { reply(callback,request,@{},nil); }
        else if ([method isEqualToString:@"show"]) { [host show:[p[@"dark"] boolValue]]; reply(callback,request,@{},nil); }
        else if ([method isEqualToString:@"hide"]) { [host hide]; reply(callback,request,@{},nil); }
        else if ([method isEqualToString:@"status"]) {
            reply(callback,request,@{@"cancelled":@(host.cancelled),@"loading":@(host.web.loading),@"error":host.navigationError ?: @""},nil);
        }
        else if ([method isEqualToString:@"navigate"]) {
            NSURL *url = [NSURL URLWithString:p[@"url"]];
            if (!([url.scheme isEqualToString:@"https"] && [url.host isEqualToString:@"linux.do"]) && ![url.absoluteString isEqualToString:@"about:blank"]) { reply(callback,request,nil,@"Invalid navigation"); return; }
            [host.web loadRequest:[NSURLRequest requestWithURL:url]];
            reply(callback,request,@{},nil);
        }
        else if ([method isEqualToString:@"evaluate"]) {
            NSString *body = [NSString stringWithFormat:@"return await (%@);",p[@"script"]];
            WKContentWorld *world = [p[@"isolated"] boolValue] ? WKContentWorld.defaultClientWorld : WKContentWorld.pageWorld;
            [host.web callAsyncJavaScript:body arguments:@{} inFrame:nil inContentWorld:world completionHandler:^(id result, NSError *error) {
                NSString *failure = error ? [NSString stringWithFormat:@"WebKit operation failed (%@:%ld)",error.domain,(long)error.code] : nil;
                reply(callback,request,result,failure);
            }];
        }
        else if ([method isEqualToString:@"cookies"]) {
            [store getAllCookies:^(NSArray<NSHTTPCookie *> *cookies) {
                NSMutableArray *result = [NSMutableArray new];
                for (NSHTTPCookie *cookie in cookies) {
                    NSString *domain = [cookie.domain hasPrefix:@"."] ? [cookie.domain substringFromIndex:1] : cookie.domain;
                    if (forumHost(domain)) [result addObject:@{@"name":cookie.name,@"value":cookie.value,@"domain":cookie.domain,@"path":cookie.path}];
                }
                reply(callback,request,@{@"cookies":result},nil);
            }];
        }
        else if ([method isEqualToString:@"setCookies"]) {
            dispatch_group_t group = dispatch_group_create();
            NSDate *expires = [NSDate dateWithTimeIntervalSinceNow:86400 * 180];
            for (NSDictionary *item in p[@"cookies"]) {
                NSMutableDictionary *props = [@{
                    NSHTTPCookieName: item[@"name"],
                    NSHTTPCookieValue: item[@"value"],
                    NSHTTPCookieDomain: @"linux.do",
                    NSHTTPCookieOriginURL: @"https://linux.do/",
                    NSHTTPCookiePath: @"/",
                    NSHTTPCookieSecure: @"TRUE",
                    NSHTTPCookieExpires: expires,
                    @"HttpOnly": @"TRUE"
                } mutableCopy];
                NSHTTPCookie *cookie = [NSHTTPCookie cookieWithProperties:props];
                if (cookie) { dispatch_group_enter(group); [store setCookie:cookie completionHandler:^{dispatch_group_leave(group);}]; }
            }
            dispatch_group_notify(group,dispatch_get_main_queue(),^{reply(callback,request,@{},nil);});
        }
        else if ([method isEqualToString:@"clearCookies"] || [method isEqualToString:@"deleteCookie"]) {
            [store getAllCookies:^(NSArray<NSHTTPCookie *> *cookies) {
                dispatch_group_t group = dispatch_group_create();
                for (NSHTTPCookie *cookie in cookies) {
                    NSString *domain = [cookie.domain hasPrefix:@"."] ? [cookie.domain substringFromIndex:1] : cookie.domain;
                    BOOL matches = [method isEqualToString:@"clearCookies"] || ([cookie.name isEqual:p[@"name"]] && [cookie.domain isEqual:p[@"domain"]] && [cookie.path isEqual:p[@"path"]]);
                    if (forumHost(domain) && matches) { dispatch_group_enter(group); [store deleteCookie:cookie completionHandler:^{dispatch_group_leave(group);}]; }
                }
                dispatch_group_notify(group,dispatch_get_main_queue(),^{reply(callback,request,@{},nil);});
            }];
        } else reply(callback,request,nil,@"Unknown native command");
    });
}
