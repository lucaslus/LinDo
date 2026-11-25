#import <Cocoa/Cocoa.h>
#import <WebKit/WebKit.h>
#import <Security/Security.h>
#import <Network/Network.h>
#import <CFNetwork/CFNetwork.h>
#include <ifaddrs.h>
#include <arpa/inet.h>
#include <net/if.h>

extern int lindo_ech_verify_leaf_der(const uint8_t *der_ptr, size_t der_len, const char *host_utf8);

static void lindoHandleWebKitAuthChallenge(
    NSURLAuthenticationChallenge *challenge,
    void (^completionHandler)(NSURLSessionAuthChallengeDisposition, NSURLCredential * _Nullable)
) {
    if (![challenge.protectionSpace.authenticationMethod isEqualToString:NSURLAuthenticationMethodServerTrust]) {
        completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, nil);
        return;
    }
    SecTrustRef trust = challenge.protectionSpace.serverTrust;
    NSString *host = challenge.protectionSpace.host ?: @"";
    if (!trust || !host.length) {
        completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, nil);
        return;
    }
    CFDataRef leafDer = NULL;
    if (@available(macOS 12.0, *)) {
        CFArrayRef chain = SecTrustCopyCertificateChain(trust);
        if (chain) {
            if (CFArrayGetCount(chain) > 0) {
                SecCertificateRef leaf = (SecCertificateRef)CFArrayGetValueAtIndex(chain, 0);
                if (leaf) {
                    leafDer = SecCertificateCopyData(leaf);
                }
            }
            CFRelease(chain);
        }
    }
    if (!leafDer) {
        #pragma clang diagnostic push
        #pragma clang diagnostic ignored "-Wdeprecated-declarations"
        if (SecTrustGetCertificateCount(trust) > 0) {
            SecCertificateRef leaf = SecTrustGetCertificateAtIndex(trust, 0);
            if (leaf) {
                leafDer = SecCertificateCopyData(leaf);
            }
        }
        #pragma clang diagnostic pop
    }
    BOOL verified = NO;
    if (leafDer) {
        const uint8_t *bytes = (const uint8_t *)CFDataGetBytePtr(leafDer);
        size_t len = (size_t)CFDataGetLength(leafDer);
        const char *hostUtf8 = host.UTF8String;
        if (bytes && len > 0 && hostUtf8) {
            verified = (lindo_ech_verify_leaf_der(bytes, len, hostUtf8) == 1);
        }
        CFRelease(leafDer);
    }
    if (verified) {
        completionHandler(NSURLSessionAuthChallengeUseCredential, [NSURLCredential credentialForTrust:trust]);
    } else {
        completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, nil);
    }
}

__attribute__((visibility("default"))) int lindo_macos_supports_wk_proxy(void) {
    if (@available(macOS 14.0, *)) {
        return 1;
    }
    return 0;
}

__attribute__((visibility("default"))) void lindo_macos_set_webkit_proxy(uint16_t port) {
    void (^applyBlock)(void) = ^{
        if (@available(macOS 14.0, *)) {
            WKWebsiteDataStore *store = WKWebsiteDataStore.defaultDataStore;
            if (!store) return;
            if (port > 0) {
                char portBuf[16];
                snprintf(portBuf, sizeof(portBuf), "%u", (unsigned int)port);
                nw_endpoint_t ep = nw_endpoint_create_host("127.0.0.1", portBuf);
                nw_proxy_config_t cfg = ep ? nw_proxy_config_create_http_connect(ep, NULL) : nil;
                if (cfg) {
                    nw_proxy_config_add_match_domain(cfg, "linux.do");
                    nw_proxy_config_add_match_domain(cfg, "ldstatic.com");
                    nw_proxy_config_add_match_domain(cfg, "idcflare.com");
                    nw_proxy_config_add_match_domain(cfg, "challenges.cloudflare.com");
                    nw_proxy_config_add_excluded_domain(cfg, "localhost");
                    store.proxyConfigurations = @[];
                    store.proxyConfigurations = @[cfg];
                }
            } else {
                store.proxyConfigurations = @[];
            }
        }
    };
    if ([NSThread isMainThread]) {
        applyBlock();
    } else {
        dispatch_async(dispatch_get_main_queue(), applyBlock);
    }
}

__attribute__((visibility("default"))) void lindo_macos_detect_proxy_json(const char *local_ip_utf8, char *buf, size_t buf_len) {
    if (!buf || buf_len == 0) return;
    buf[0] = '\0';
    @autoreleasepool {
        BOOL sysProxy = NO;
        NSString *httpProxy = @"";
        NSDictionary *settings = CFBridgingRelease(CFNetworkCopySystemProxySettings());
        if ([settings isKindOfClass:NSDictionary.class]) {
            BOOL httpEn = [settings[(__bridge NSString *)kCFNetworkProxiesHTTPEnable] boolValue];
            BOOL httpsEn = [settings[(__bridge NSString *)kCFNetworkProxiesHTTPSEnable] boolValue];
            BOOL socksEn = [settings[(__bridge NSString *)kCFNetworkProxiesSOCKSEnable] boolValue];
            BOOL pacEn = [settings[(__bridge NSString *)kCFNetworkProxiesProxyAutoConfigEnable] boolValue];
            BOOL autoDisc = [settings[(__bridge NSString *)kCFNetworkProxiesProxyAutoDiscoveryEnable] boolValue];
            sysProxy = httpEn || httpsEn || socksEn || pacEn || autoDisc;
            if (httpEn) {
                NSString *h = settings[(__bridge NSString *)kCFNetworkProxiesHTTPProxy];
                NSNumber *p = settings[(__bridge NSString *)kCFNetworkProxiesHTTPPort];
                if ([h isKindOfClass:NSString.class] && h.length > 0 && p.intValue > 0) {
                    httpProxy = [NSString stringWithFormat:@"%@:%d", h, p.intValue];
                }
            } else if (httpsEn) {
                NSString *h = settings[(__bridge NSString *)kCFNetworkProxiesHTTPSProxy];
                NSNumber *p = settings[(__bridge NSString *)kCFNetworkProxiesHTTPSPort];
                if ([h isKindOfClass:NSString.class] && h.length > 0 && p.intValue > 0) {
                    httpProxy = [NSString stringWithFormat:@"%@:%d", h, p.intValue];
                }
            }
        }

        NSString *routeIface = @"";
        BOOL hasTunIface = NO;
        NSString *targetIp = (local_ip_utf8 && local_ip_utf8[0] != '\0')
            ? [NSString stringWithUTF8String:local_ip_utf8]
            : @"";
        if (targetIp.length > 0) {
            struct ifaddrs *ifap = NULL;
            if (getifaddrs(&ifap) == 0 && ifap != NULL) {
                for (struct ifaddrs *ifa = ifap; ifa != NULL; ifa = ifa->ifa_next) {
                    if (!ifa->ifa_addr || !ifa->ifa_name) continue;
                    if (ifa->ifa_addr->sa_family == AF_INET) {
                        char addrStr[INET_ADDRSTRLEN] = {0};
                        struct sockaddr_in *sin = (struct sockaddr_in *)ifa->ifa_addr;
                        if (inet_ntop(AF_INET, &sin->sin_addr, addrStr, sizeof(addrStr))) {
                            if ([targetIp isEqualToString:[NSString stringWithUTF8String:addrStr]]) {
                                routeIface = [NSString stringWithUTF8String:ifa->ifa_name] ?: @"";
                                break;
                            }
                        }
                    }
                }
                freeifaddrs(ifap);
            }
            NSString *lowerIface = routeIface.lowercaseString;
            if ([lowerIface hasPrefix:@"utun"] ||
                [lowerIface hasPrefix:@"ppp"] ||
                [lowerIface hasPrefix:@"ipsec"] ||
                [targetIp hasPrefix:@"198.18."] ||
                [targetIp hasPrefix:@"198.19."]) {
                hasTunIface = YES;
            }
        }

        NSDictionary *out = @{
            @"sysProxy": @(sysProxy),
            @"httpProxy": httpProxy,
            @"routeIface": routeIface,
            @"hasTunIface": @(hasTunIface),
        };
        NSData *jsonData = [NSJSONSerialization dataWithJSONObject:out options:0 error:nil];
        if (jsonData.length > 0) {
            size_t copyLen = MIN(jsonData.length, buf_len - 1);
            memcpy(buf, jsonData.bytes, copyLen);
            buf[copyLen] = '\0';
        }
    }
}

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

static nw_path_monitor_t gNetPathMonitor = NULL;
static NSString *gLastNetPathSignature = nil;

static void installNetworkPathMonitorOnce(void) {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        nw_path_monitor_t monitor = nw_path_monitor_create();
        if (!monitor) return;
        gNetPathMonitor = monitor;
        dispatch_queue_t q = dispatch_queue_create("do.linux.lindo.netpath", DISPATCH_QUEUE_SERIAL);
        nw_path_monitor_set_update_handler(monitor, ^(nw_path_t path) {
            nw_path_status_t st = nw_path_get_status(path);
            if (st != nw_path_status_satisfied) return;
            BOOL wifi = nw_path_uses_interface_type(path, nw_interface_type_wifi);
            BOOL wired = nw_path_uses_interface_type(path, nw_interface_type_wired);
            BOOL cell = nw_path_uses_interface_type(path, nw_interface_type_cellular);
            BOOL other = nw_path_uses_interface_type(path, nw_interface_type_other); // VPN / utun
            BOOL expensive = nw_path_is_expensive(path);
            NSString *sig = [NSString stringWithFormat:@"w%d_e%d_c%d_v%d_x%d", wifi, wired, cell, other, expensive];
            if (!gLastNetPathSignature) {
                gLastNetPathSignature = sig;
                return;
            }
            if (![gLastNetPathSignature isEqualToString:sig]) {
                gLastNetPathSignature = sig;
                if (gEventReply) {
                    sendReply(gEventReply, 0, @{ @"networkChanged": sig }, nil);
                }
            }
        });
        nw_path_monitor_set_queue(monitor, q);
        nw_path_monitor_start(monitor);
    });
}

static BOOL isForumHost(NSString *host) {
    NSString *h = host.lowercaseString;
    return [h isEqualToString:@"linux.do"] || [h hasSuffix:@".linux.do"];
}

// Match the installed macOS Safari version so WKWebView sends a 100% authentic macOS Safari User-Agent
// (a bare WKWebView omits `Version/... Safari/605.1.15`, which Cloudflare WAF flags as an embedded non-browser client).
static NSString *lindoSafariAppNameForUserAgent(void) {
    static NSString *cached = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        NSString *ver = nil;
        NSDictionary *plist = [NSDictionary dictionaryWithContentsOfFile:@"/Applications/Safari.app/Contents/Info.plist"];
        id rawVer = plist[@"CFBundleShortVersionString"];
        if ([rawVer isKindOfClass:NSString.class] && ((NSString *)rawVer).length > 0) {
            ver = (NSString *)rawVer;
        }
        if (!ver.length) {
            ver = @"18.3";
        }
        cached = [NSString stringWithFormat:@"Version/%@ Safari/605.1.15", ver];
    });
    return cached;
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

static NSString *lindoMimeForExtension(NSString *rawExt) {
    NSString *ext = rawExt.lowercaseString ?: @"";
    if ([ext isEqualToString:@"png"]) return @"image/png";
    if ([ext isEqualToString:@"jpg"] || [ext isEqualToString:@"jpeg"]) return @"image/jpeg";
    if ([ext isEqualToString:@"gif"]) return @"image/gif";
    if ([ext isEqualToString:@"webp"]) return @"image/webp";
    if ([ext isEqualToString:@"svg"]) return @"image/svg+xml";
    if ([ext isEqualToString:@"avif"]) return @"image/avif";
    if ([ext isEqualToString:@"bmp"]) return @"image/bmp";
    if ([ext isEqualToString:@"tif"] || [ext isEqualToString:@"tiff"]) return @"image/tiff";
    if ([ext isEqualToString:@"heic"]) return @"image/heic";
    if ([ext isEqualToString:@"heif"]) return @"image/heif";
    if ([ext isEqualToString:@"pdf"]) return @"application/pdf";
    if ([ext isEqualToString:@"txt"]) return @"text/plain";
    if ([ext isEqualToString:@"md"]) return @"text/markdown";
    if ([ext isEqualToString:@"json"]) return @"application/json";
    if ([ext isEqualToString:@"zip"]) return @"application/zip";
    if ([ext isEqualToString:@"mp4"]) return @"video/mp4";
    if ([ext isEqualToString:@"webm"]) return @"video/webm";
    if ([ext isEqualToString:@"mov"]) return @"video/quicktime";
    return @"application/octet-stream";
}

static BOOL lindoNeedsPngConversion(NSString *rawExt) {
    NSString *ext = rawExt.lowercaseString ?: @"";
    return [ext isEqualToString:@"tif"]
        || [ext isEqualToString:@"tiff"]
        || [ext isEqualToString:@"heic"]
        || [ext isEqualToString:@"heif"]
        || [ext isEqualToString:@"bmp"];
}

static NSData *lindoConvertImageDataToPng(NSData *rawData) {
    if (!rawData.length) return nil;
    @autoreleasepool {
        NSBitmapImageRep *bitmap = [NSBitmapImageRep imageRepWithData:rawData];
        if (!bitmap) {
            NSImage *img = [[NSImage alloc] initWithData:rawData];
            if (img) {
                CGImageRef cgImg = [img CGImageForProposedRect:NULL context:nil hints:nil];
                if (cgImg) {
                    bitmap = [[NSBitmapImageRep alloc] initWithCGImage:cgImg];
                }
            }
        }
        if (!bitmap) return nil;
        return [bitmap representationUsingType:NSBitmapImageFileTypePNG properties:@{}];
    }
}

static NSDictionary *lindoReadLocalMediaFile(NSString *path, NSUInteger maxBytes) {
    if (!path.length) return nil;
    NSFileManager *fm = [NSFileManager defaultManager];
    BOOL isDir = NO;
    if (![fm fileExistsAtPath:path isDirectory:&isDir] || isDir) return nil;
    NSDictionary *attrs = [fm attributesOfItemAtPath:path error:nil];
    unsigned long long fileSize = [attrs[NSFileSize] unsignedLongLongValue];
    if (fileSize == 0 || fileSize > (unsigned long long)maxBytes * 2ULL) return nil;

    NSData *raw = [NSData dataWithContentsOfFile:path];
    if (!raw.length) return nil;

    NSString *name = path.lastPathComponent ?: @"attachment";
    NSString *ext = name.pathExtension.lowercaseString ?: @"";
    NSData *finalData = raw;
    NSString *mime = lindoMimeForExtension(ext);

    if (lindoNeedsPngConversion(ext)) {
        NSData *png = lindoConvertImageDataToPng(raw);
        if (png.length > 0 && png.length <= maxBytes) {
            finalData = png;
            mime = @"image/png";
            NSString *stem = [name stringByDeletingPathExtension];
            name = [NSString stringWithFormat:@"%@.png", stem.length ? stem : @"image"];
        }
    }
    if (finalData.length == 0 || finalData.length > maxBytes) return nil;
    return @{
        @"filename": name,
        @"mime": mime,
        @"dataBase64": [finalData base64EncodedStringWithOptions:0]
    };
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
@property BOOL userReloading;
@property NSUInteger reloadEpoch;
@property NSProgressIndicator *progress;
@property NSButton *browserAuthBtn;
@property BOOL cancelled;
@property BOOL processTerminated;
@property NSString *navigationError;
@property id<NSObject> activityToken;
- (void)handleCfScriptEvent:(NSString *)eventName;
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
        config.applicationNameForUserAgent = lindoSafariAppNameForUserAgent();

        WKUserContentController *ucc = [WKUserContentController new];
        LindoCfScriptHandler *cfHandler = [LindoCfScriptHandler new];
        cfHandler.host = self;
        // Register message handler & passive lifecycle observer strictly inside isolated `defaultClientWorld`
        // so `window.webkit` is never exposed to page scripts and zero DOM/CSS tampering ever touches `/challenge`.
        [ucc addScriptMessageHandler:cfHandler contentWorld:WKContentWorld.defaultClientWorld name:@"lindoCf"];

        NSString *cfLifecycleJs =
            @"(function(){"
             "  var sentInteractive=false;"
             "  function post(ev){try{window.webkit.messageHandlers.lindoCf.postMessage(ev);}catch(_){}}"
             "  function postInteractiveOnce(){if(!sentInteractive){sentInteractive=true;post('interactive');}}"
             "  function checkDom(){"
             "    try{"
             "      if(location.origin==='https://linux.do'&&location.pathname.indexOf('/challenge')===0&&document.querySelector('#challenge-stage, .cf-turnstile, #turnstile-wrapper, iframe[src*=\"challenges.cloudflare.com\"]')){"
             "        postInteractiveOnce();"
             "        return true;"
             "      }"
             "    }catch(_){}"
             "    return false;"
             "  }"
             "  window.addEventListener('message',function(e){"
             "    try{"
             "      var d=e&&e.data;"
             "      if(typeof d==='string'&&d.charAt(0)==='{'){d=JSON.parse(d);}"
             "      if(d&&d.source==='cloudflare-challenge'&&(d.event==='interactiveBegin'||d.event==='interactiveTimeout')){"
             "        postInteractiveOnce();"
             "      }"
             "    }catch(_){}"
             "  },false);"
             "  if(!checkDom()&&typeof MutationObserver!=='undefined'){"
             "    var attachMo=function(){"
             "      if(checkDom()||!document.documentElement)return;"
             "      var mo=new MutationObserver(function(){if(checkDom()){try{mo.disconnect();}catch(_){}}});"
             "      mo.observe(document.documentElement,{childList:true,subtree:true});"
             "    };"
             "    if(document.documentElement){attachMo();}else{document.addEventListener('DOMContentLoaded',attachMo,{once:true});}"
             "  }"
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
                                                            forMainFrameOnly:YES
                                                              inContentWorld:WKContentWorld.defaultClientWorld];
        [ucc addUserScript:lifecycleScript];
        config.userContentController = ucc;

        // Keep `_web` unparented offscreen with a normal desktop viewport until `show:loginMode:` attaches it.
        _web = [[WKWebView alloc] initWithFrame:NSMakeRect(0, 0, 1280, 800) configuration:config];
        if ([_web respondsToSelector:NSSelectorFromString(@"_setDrawsBackground:")]) {
            @try {
                [_web setValue:@NO forKey:@"drawsBackground"];
            } @catch (NSException *ignored) {}
        }
        if (@available(macOS 12.0, *)) {
            if ([_web respondsToSelector:@selector(setUnderPageBackgroundColor:)]) {
                [_web setUnderPageBackgroundColor:[NSColor colorWithRed:0.094 green:0.114 blue:0.153 alpha:1.0]];
            }
        }
        _web.navigationDelegate = self;
        _web.UIDelegate = self;
        _maskVisibleState = NO;
        _maskDarkState = YES;
        _maskMessageState = @"校验连接中…";
        _loginModeState = NO;
        _challengeOriginReached = NO;
        _challengeInteractiveRequired = NO;
        _userReloading = NO;
        _reloadEpoch = 0;
    }
    return self;
}

- (void)handleCfScriptEvent:(NSString *)eventName {
    if ([eventName isEqualToString:@"interactive"]) {
        self.challengeInteractiveRequired = YES;
        self.userReloading = NO;
        if (!self.loginModeState && _panel && _panel.isVisible && !self.challengeOriginReached) {
            [self setVerifyMask:NO dark:self.maskDarkState message:@""];
        }
        if (gEventReply) {
            sendReply(gEventReply, 0, @{ @"cfVerifyEvent": @"interactive" }, nil);
        }
    } else if ([eventName isEqualToString:@"unloading"]) {
        if (!self.loginModeState && !self.userReloading) {
            // Instant 0ms mask cover BEFORE the post-challenge 404 navigation even starts!
            [self setVerifyMask:YES dark:self.maskDarkState message:@"已通过，正在继续…"];
        }
    }
}

- (void)layoutCardForMode:(BOOL)loginMode dark:(BOOL)dark owner:(NSWindow *)owner {
    CGFloat cardW = loginMode ? 540.0 : 480.0;
    CGFloat cardH = loginMode ? 620.0 : 380.0;
    if (owner) {
        if (loginMode) {
            cardW = MAX(440.0, MIN(580.0, owner.frame.size.width - 64.0));
            cardH = MAX(480.0, MIN(640.0, owner.frame.size.height - 72.0));
        } else {
            cardW = MAX(420.0, MIN(500.0, owner.frame.size.width - 80.0));
            cardH = MAX(340.0, MIN(420.0, owner.frame.size.height - 96.0));
        }
    }
    CGFloat headerH = 40.0;
    CGFloat bodyH = MAX(260.0, cardH - headerH);

    _panel.minSize = loginMode ? NSMakeSize(420, 460) : NSMakeSize(400, 320);
    _panel.maxSize = NSMakeSize(900, 900);
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
    _web.appearance = [NSAppearance appearanceNamed:dark ? NSAppearanceNameDarkAqua : NSAppearanceNameAqua];
    if (@available(macOS 12.0, *)) {
        if ([_web respondsToSelector:@selector(setUnderPageBackgroundColor:)]) {
            [_web setUnderPageBackgroundColor:cardBg];
        }
    }

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
    // Always fill the card body naturally so Cloudflare's challenge page is never clipped or shifted off-screen.
    _web.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    [_web setFrame:NSMakeRect(0, 0, cardW, bodyH)];
}

- (void)ensurePanelCreated {
    if (_panel) return;
    _panel = [[LindoBridgePanel alloc]
        initWithContentRect:NSMakeRect(0, 0, 480, 380)
                  styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable | NSWindowStyleMaskResizable | NSWindowStyleMaskFullSizeContentView
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

    NSView *root = [[NSView alloc] initWithFrame:NSMakeRect(0, 0, 480, 380)];
    root.wantsLayer = YES;
    root.layer.backgroundColor = [NSColor colorWithRed:0.094 green:0.114 blue:0.153 alpha:1.0].CGColor;
    _panelRoot = root;

    _webClipView = [[NSView alloc] initWithFrame:NSMakeRect(0, 0, 480, 340)];
    _webClipView.wantsLayer = YES;
    _webClipView.layer.masksToBounds = YES;
    _webClipView.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    [root addSubview:_webClipView];

    _maskView = [[LindoVerifyMaskView alloc] initWithFrame:NSMakeRect(0, 0, 480, 340)];
    _maskView.wantsLayer = YES;
    _maskView.layer.backgroundColor = [NSColor colorWithRed:0.094 green:0.114 blue:0.153 alpha:1.0].CGColor;
    _maskView.layer.zPosition = 100.0;
    _maskView.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    _maskView.hidden = YES;

    _maskSpinner = [[NSProgressIndicator alloc] initWithFrame:NSMakeRect(231, 176, 18, 18)];
    _maskSpinner.style = NSProgressIndicatorStyleSpinning;
    _maskSpinner.controlSize = NSControlSizeSmall;
    _maskSpinner.displayedWhenStopped = NO;
    _maskSpinner.autoresizingMask = NSViewMinXMargin | NSViewMaxXMargin | NSViewMinYMargin | NSViewMaxYMargin;
    [_maskView addSubview:_maskSpinner];

    _maskLabel = [NSTextField labelWithString:@"校验连接中…"];
    _maskLabel.textColor = NSColor.secondaryLabelColor;
    _maskLabel.font = [NSFont systemFontOfSize:12.2 weight:NSFontWeightMedium];
    _maskLabel.alignment = NSTextAlignmentCenter;
    _maskLabel.frame = NSMakeRect(16, 150, 448, 18);
    _maskLabel.autoresizingMask = NSViewWidthSizable | NSViewMinYMargin | NSViewMaxYMargin;
    [_maskView addSubview:_maskLabel];

    [root addSubview:_maskView];

    // Borderless top header bar (zero hairline between header & content!)
    NSView *bar = [[NSView alloc] initWithFrame:NSMakeRect(0, 340, 480, 40)];
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
    _browserAuthBtn.autoresizingMask = NSViewMinXMargin;
    [bar addSubview:_browserAuthBtn];

    _progress = [[NSProgressIndicator alloc] initWithFrame:NSMakeRect(398, 12, 16, 16)];
    _progress.style = NSProgressIndicatorStyleSpinning;
    _progress.controlSize = NSControlSizeSmall;
    _progress.displayedWhenStopped = NO;
    _progress.autoresizingMask = NSViewMinXMargin;
    [bar addSubview:_progress];

    NSImageSymbolConfiguration *btnSymCfg = [NSImageSymbolConfiguration configurationWithPointSize:11.5 weight:NSFontWeightMedium];
    NSImage *reloadImg = [[NSImage imageWithSystemSymbolName:@"arrow.clockwise" accessibilityDescription:@"重新加载"] imageWithSymbolConfiguration:btnSymCfg];
    _reloadBtn = [NSButton buttonWithImage:reloadImg target:self action:@selector(reloadPage:)];
    _reloadBtn.bordered = NO;
    _reloadBtn.autoresizingMask = NSViewMinXMargin;
    [bar addSubview:_reloadBtn];

    NSImage *closeImg = [[NSImage imageWithSystemSymbolName:@"xmark" accessibilityDescription:@"关闭"] imageWithSymbolConfiguration:btnSymCfg];
    _closeBtn = [NSButton buttonWithImage:closeImg target:self action:@selector(closePanel:)];
    _closeBtn.bordered = NO;
    _closeBtn.autoresizingMask = NSViewMinXMargin;
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
        self.userReloading = YES;
        self.reloadEpoch += 1;
        self.challengeOriginReached = NO;
        self.challengeInteractiveRequired = NO;
        self.navigationError = nil;
        [self setVerifyMask:YES dark:self.maskDarkState message:@"正在重新加载验证…"];
        NSURL *challengeUrl = [NSURL URLWithString:@"https://linux.do/challenge"];
        if (challengeUrl) {
            [_web stopLoading];
            NSURLRequest *req = [NSURLRequest requestWithURL:challengeUrl
                                                 cachePolicy:NSURLRequestReloadIgnoringLocalCacheData
                                             timeoutInterval:20.0];
            [_web loadRequest:req];
            return;
        }
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
    NSColor *bg = _maskDarkState
        ? [NSColor colorWithRed:0.094 green:0.114 blue:0.153 alpha:1.0]
        : [NSColor colorWithRed:0.976 green:0.980 blue:0.988 alpha:1.0];
    _web.appearance = [NSAppearance appearanceNamed:_maskDarkState ? NSAppearanceNameDarkAqua : NSAppearanceNameAqua];
    if (@available(macOS 12.0, *)) {
        if ([_web respondsToSelector:@selector(setUnderPageBackgroundColor:)]) {
            [_web setUnderPageBackgroundColor:bg];
        }
    }
    if (!_maskView) return;
    _maskView.layer.backgroundColor = bg.CGColor;
    _maskLabel.textColor = _maskDarkState
        ? [NSColor colorWithRed:0.640 green:0.675 blue:0.740 alpha:1.0]
        : [NSColor colorWithRed:0.322 green:0.345 blue:0.400 alpha:1.0];
    if (_maskMessageState.length > 0) {
        _maskLabel.stringValue = _maskMessageState;
    }
    if (_maskVisibleState) {
        _maskView.hidden = NO;
        // Keep WKWebView at full alpha while loading Turnstile behind the opaque mask so WebKit never throttles
        // timers/rendering/IntersectionObserver; only drop alpha to 0 once origin is reached (post-verification).
        _web.alphaValue = self.challengeOriginReached ? 0.0 : 1.0;
        if (_panel && _panel.isVisible) {
            [_maskSpinner startAnimation:nil];
        }
    } else {
        [_maskSpinner stopAnimation:nil];
        _web.alphaValue = 1.0;
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
    self.userReloading = NO;
    if (_panel && _panel.isVisible) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(450 * NSEC_PER_MSEC)), dispatch_get_main_queue(), ^{
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
    self.userReloading = NO;
    self.navigationError = error.localizedDescription ?: @"Navigation failed";
    if (!self.loginModeState && _panel && _panel.isVisible && _maskVisibleState) {
        [self setVerifyMask:YES dark:self.maskDarkState message:@"连接中断，请点右上角刷新重试"];
    }
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
    if (allowed && !self.loginModeState && !self.userReloading && self.challengeInteractiveRequired && [url.path isEqualToString:@"/challenge"] && !url.query.length) {
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
        if (!self.loginModeState && isForumHost(host)) {
            if (isCfChallenge) {
                self.challengeOriginReached = NO;
                if (_maskVisibleState && [_maskMessageState isEqualToString:@"已通过，正在继续…"]) {
                    [self setVerifyMask:YES dark:self.maskDarkState message:@"校验连接中…"];
                }
            } else if ([path hasPrefix:@"/challenge"] && (http.statusCode == 404 || (http.statusCode >= 200 && http.statusCode < 400))) {
                self.challengeOriginReached = YES;
                [self setVerifyMask:YES dark:self.maskDarkState message:@"已通过，正在继续…"];
                if (gEventReply) {
                    sendReply(gEventReply, 0, @{ @"cfVerifyEvent": @"origin404" }, nil);
                }
            } else if (http.statusCode == 200 || http.statusCode == 404) {
                [self setVerifyMask:YES dark:self.maskDarkState message:@"已通过，正在继续…"];
            } else if (http.statusCode >= 500) {
                self.challengeOriginReached = NO;
                [self setVerifyMask:YES dark:self.maskDarkState message:@"站点临时繁忙，请点右上角刷新重试"];
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
- (void)webView:(WKWebView *)webView didReceiveAuthenticationChallenge:(NSURLAuthenticationChallenge *)challenge completionHandler:(void (^)(NSURLSessionAuthChallengeDisposition, NSURLCredential * _Nullable))completionHandler {
    lindoHandleWebKitAuthChallenge(challenge, completionHandler);
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
        config.applicationNameForUserAgent = lindoSafariAppNameForUserAgent();

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
- (void)webView:(WKWebView *)webView didReceiveAuthenticationChallenge:(NSURLAuthenticationChallenge *)challenge completionHandler:(void (^)(NSURLSessionAuthChallengeDisposition, NSURLCredential * _Nullable))completionHandler {
    lindoHandleWebKitAuthChallenge(challenge, completionHandler);
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
        cfg.applicationNameForUserAgent = lindoSafariAppNameForUserAgent();
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
- (void)webView:(WKWebView *)webView didReceiveAuthenticationChallenge:(NSURLAuthenticationChallenge *)challenge completionHandler:(void (^)(NSURLSessionAuthChallengeDisposition, NSURLCredential * _Nullable))completionHandler {
    lindoHandleWebKitAuthChallenge(challenge, completionHandler);
}
@end

static id gFeatNear60Fps = nil;
static id gFeatCustomFrameRate = nil;
static BOOL gHighRefreshFeaturesResolved = NO;
static id gScrollWheelMonitor = nil;
static BOOL gSmoothWheelJsReady = NO;
static CFAbsoluteTime gLastZeroPhaseWheelTime = 0;
static NSInteger gSmoothDaemonBurstCount = 0;

static void resolveHighRefreshFeatureRefs(void) {
    if (gHighRefreshFeaturesResolved) return;
    gHighRefreshFeaturesResolved = YES;
    SEL featSel = NSSelectorFromString(@"_features");
    if (![WKPreferences respondsToSelector:featSel]) return;
    #pragma clang diagnostic push
    #pragma clang diagnostic ignored "-Warc-performSelector-leaks"
    NSArray *features = [WKPreferences performSelector:featSel];
    for (id feat in features) {
        NSString *key = [feat valueForKey:@"key"];
        if ([key isEqualToString:@"PreferPageRenderingUpdatesNear60FPSEnabled"]) {
            gFeatNear60Fps = feat;
        } else if ([key isEqualToString:@"WebAnimationsCustomFrameRateEnabled"]) {
            gFeatCustomFrameRate = feat;
        }
    }
    #pragma clang diagnostic pop
}

static BOOL isWebKitFeatureEnabled(WKPreferences *prefs, id feat, BOOL fallback) {
    if (!prefs || !feat) return fallback;
    SEL getSel = NSSelectorFromString(@"_isEnabledForFeature:");
    if (![prefs respondsToSelector:getSel]) return fallback;
    NSMethodSignature *sig = [prefs methodSignatureForSelector:getSel];
    if (!sig) return fallback;
    NSInvocation *inv = [NSInvocation invocationWithMethodSignature:sig];
    inv.target = prefs;
    inv.selector = getSel;
    id featVar = feat;
    [inv setArgument:&featVar atIndex:2];
    [inv invoke];
    BOOL enabled = fallback;
    [inv getReturnValue:&enabled];
    return enabled;
}

static void setWebKitFeatureEnabled(WKPreferences *prefs, id feat, BOOL enable) {
    if (!prefs || !feat) return;
    SEL setSel = NSSelectorFromString(@"_setEnabled:forFeature:");
    if (![prefs respondsToSelector:setSel]) return;
    NSMethodSignature *sig = [prefs methodSignatureForSelector:setSel];
    if (!sig) return;
    NSInvocation *inv = [NSInvocation invocationWithMethodSignature:sig];
    inv.target = prefs;
    inv.selector = setSel;
    id featVar = feat;
    [inv setArgument:&enable atIndex:2];
    [inv setArgument:&featVar atIndex:3];
    [inv invoke];
}

static void unlockHighRefreshRateForWebView(WKWebView *wv) {
    if (!wv) return;
    WKPreferences *prefs = wv.configuration.preferences;
    if (!prefs) return;
    resolveHighRefreshFeatureRefs();
    if (gFeatNear60Fps && isWebKitFeatureEnabled(prefs, gFeatNear60Fps, YES)) {
        setWebKitFeatureEnabled(prefs, gFeatNear60Fps, NO);
    }
    if (gFeatCustomFrameRate && !isWebKitFeatureEnabled(prefs, gFeatCustomFrameRate, NO)) {
        setWebKitFeatureEnabled(prefs, gFeatCustomFrameRate, YES);
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
static BOOL gDesiredPointerCursor = NO;
static BOOL gHasDesiredCursor = NO;

static BOOL isMouseInsideMainContentInset(NSWindow *win) {
    if (!win || !win.contentView) return NO;
    if ((gHost && win == gHost.panel) || (gBrowser && win == gBrowser.window)) return NO;
    NSPoint mouseScreen = [NSEvent mouseLocation];
    NSPoint ptInWin = [win convertPointFromScreen:mouseScreen];
    NSRect inner = NSInsetRect(win.contentView.bounds, 6.0, 6.0);
    return NSPointInRect(ptInWin, inner);
}

static void applyStrictTwoStateCursor(NSWindow *win, BOOL pointer) {
    if (!NSApp.isActive || !win || !win.isKeyWindow) return;
    if (!isMouseInsideMainContentInset(win)) return;
    NSCursor *target = pointer ? [NSCursor pointingHandCursor] : [NSCursor arrowCursor];
    if ([NSCursor currentCursor] != target) {
        [target set];
    }
}

__attribute__((visibility("default"))) void lindo_bridge_set_cursor(int pointer) {
    dispatch_async(dispatch_get_main_queue(), ^{
        gDesiredPointerCursor = (pointer != 0);
        gHasDesiredCursor = YES;
        applyStrictTwoStateCursor(NSApp.keyWindow, gDesiredPointerCursor);
    });
}

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
        // 1. Prevent WKWebView's `NSTrackingActiveAlways` tracking areas from overriding `NSCursor` or showing tooltips
        //    while external screenshot overlays (macOS / Feishu / WeChat / Snipaste) or other apps are active (never swallowing MouseExited).
        // 2. Inside the active main window's content area (excluding the 6px outer window-resize rim), enforce our strict
        //    two-state cursor policy (arrow vs pointing hand) so WKWebView never gets stuck on pointing hand / I-beam / resize cursors.
        NSEventMask hoverMask = NSEventMaskMouseMoved | NSEventMaskCursorUpdate | NSEventMaskMouseEntered | NSEventMaskMouseExited | NSEventMaskLeftMouseDragged;
        gInactiveHoverGuardMonitor = [NSEvent addLocalMonitorForEventsMatchingMask:hoverMask handler:^NSEvent * _Nullable(NSEvent * _Nonnull event) {
            NSEventType etype = event.type;
            NSWindow *win = event.window;
            if (!NSApp.isActive || (win && !win.isKeyWindow)) {
                if (etype == NSEventTypeMouseExited) {
                    gDesiredPointerCursor = NO;
                    return event;
                }
                return nil;
            }
            if (etype == NSEventTypeMouseExited) {
                gDesiredPointerCursor = NO;
                return event;
            }
            if (win && isMouseInsideMainContentInset(win)) {
                if (etype == NSEventTypeLeftMouseDragged) {
                    gDesiredPointerCursor = NO;
                    dispatch_async(dispatch_get_main_queue(), ^{
                        applyStrictTwoStateCursor(win, NO);
                    });
                } else if (etype == NSEventTypeCursorUpdate && gHasDesiredCursor) {
                    BOOL wantPointer = gDesiredPointerCursor;
                    dispatch_async(dispatch_get_main_queue(), ^{
                        applyStrictTwoStateCursor(win, wantPointer);
                    });
                }
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
            double absY = fabs((double)event.scrollingDeltaY);
            double absX = fabs((double)event.scrollingDeltaX);
            BOOL smallSubStep = (absY < 28.0 && absX < 28.0);
            if (smallSubStep && dtMs < 22.0) {
                gSmoothDaemonBurstCount++;
            } else if (!smallSubStep || dtMs > 90.0) {
                gSmoothDaemonBurstCount = 0;
            }
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
            installNetworkPathMonitorOnce();
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
                @"userReloading": @(gHost.userReloading),
                @"reloadEpoch": @(gHost.reloadEpoch),
                @"maskVisible": @(gHost.maskVisibleState),
                @"maskMessage": gHost.maskMessageState ?: @"",
                @"bridgeUa": lindoSafariAppNameForUserAgent(),
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
            gHost.userReloading = [url.path hasPrefix:@"/challenge"];
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
            NSMutableArray<NSHTTPCookie *> *canonicalCookies = [NSMutableArray new];
            for (NSDictionary *item in incoming) {
                NSString *name = item[@"name"];
                NSString *val = item[@"value"];
                // Site-owned Cloudflare cookies remain 100% managed by WKHTTPCookieStore; never delete or synthesize them.
                if (![name isKindOfClass:NSString.class] || !name.length || isCloudflareDomainCookieName(name)) continue;
                if (![val isKindOfClass:NSString.class] || !val.length) continue;
                BOOL persistent = [name isEqualToString:@"_t"];
                NSHTTPCookie *cookie = makeHostOnlyForumCookie(name, val, persistent);
                if (cookie) {
                    [targetNames addObject:name];
                    [canonicalCookies addObject:cookie];
                }
            }
            if (!canonicalCookies.count) {
                sendReply(callback, request, @{}, nil);
                return;
            }
            // 1. Insert canonical host-only Discourse session cookies FIRST (atomic in-place replacement; ZERO empty-_t window for concurrent fetch!).
            dispatch_group_t setGroup = dispatch_group_create();
            for (NSHTTPCookie *cookie in canonicalCookies) {
                dispatch_group_enter(setGroup);
                [store setCookie:cookie completionHandler:^{ dispatch_group_leave(setGroup); }];
            }
            dispatch_group_notify(setGroup, dispatch_get_main_queue(), ^{
                // 2. Prune ONLY non-canonical variants (`.linux.do` dot-domain or non-`/` path) while keeping the canonical host-only entry intact.
                [store getAllCookies:^(NSArray<NSHTTPCookie *> *existing) {
                    dispatch_group_t delGroup = dispatch_group_create();
                    BOOL deletedAny = NO;
                    for (NSHTTPCookie *c in existing) {
                        NSString *dom = [c.domain hasPrefix:@"."] ? [c.domain substringFromIndex:1] : c.domain;
                        BOOL isNonCanonical = [c.domain hasPrefix:@"."] || ![c.path isEqualToString:@"/"];
                        if ([dom.lowercaseString isEqualToString:@"linux.do"] && [targetNames containsObject:c.name] && isNonCanonical) {
                            deletedAny = YES;
                            dispatch_group_enter(delGroup);
                            [store deleteCookie:c completionHandler:^{ dispatch_group_leave(delGroup); }];
                        }
                    }
                    dispatch_group_notify(delGroup, dispatch_get_main_queue(), ^{
                        purgeSharedStorageAuthCookies(NO);
                        if (deletedAny) {
                            // Read-back barrier so WebKit Networking XPC flushes cookie store updates before caller dispatches fetch()
                            [store getAllCookies:^(NSArray<NSHTTPCookie *> *after) {
                                (void)after;
                                sendReply(callback, request, @{}, nil);
                            }];
                        } else {
                            sendReply(callback, request, @{}, nil);
                        }
                    });
                }];
            });
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
                BOOL tNeedsRewrite = tVariants.count > 1 || (tVariants.count == 1 && ([tVariants.firstObject.domain hasPrefix:@"."] || ![tVariants.firstObject.path isEqualToString:@"/"]));
                BOOL fsNeedsRewrite = fsVariants.count > 1 || (fsVariants.count == 1 && ([fsVariants.firstObject.domain hasPrefix:@"."] || ![fsVariants.firstObject.path isEqualToString:@"/"]));
                if (!tNeedsRewrite && !fsNeedsRewrite) {
                    sendReply(callback, request, @{@"swept": @NO}, nil);
                    return;
                }

                // Prefer the freshest host-only cookie (latest expiresDate, since Discourse sets Max-Age=60d on every `_t` rotation)
                // so dedupe NEVER downgrades a live rotated `_t` to a stale disk `preferredT`!
                NSString *winnerT = nil;
                if (tNeedsRewrite && tVariants.count > 0) {
                    NSHTTPCookie *bestHostOnly = nil;
                    for (NSHTTPCookie *c in tVariants) {
                        if (![c.domain hasPrefix:@"."]) {
                            if (!bestHostOnly) {
                                bestHostOnly = c;
                            } else if (c.expiresDate && (!bestHostOnly.expiresDate || [c.expiresDate compare:bestHostOnly.expiresDate] == NSOrderedDescending)) {
                                bestHostOnly = c;
                            } else if (!c.expiresDate && !bestHostOnly.expiresDate) {
                                bestHostOnly = c;
                            }
                        }
                    }
                    if (bestHostOnly) {
                        winnerT = bestHostOnly.value;
                    }
                    if (!winnerT && preferredT.length > 0) {
                        for (NSHTTPCookie *c in tVariants) {
                            if ([c.value isEqualToString:preferredT]) { winnerT = c.value; break; }
                        }
                    }
                    if (!winnerT) {
                        NSHTTPCookie *bestAny = tVariants.lastObject;
                        for (NSHTTPCookie *c in tVariants) {
                            if (c.expiresDate && (!bestAny.expiresDate || [c.expiresDate compare:bestAny.expiresDate] == NSOrderedDescending)) {
                                bestAny = c;
                            }
                        }
                        winnerT = bestAny.value;
                    }
                }
                NSString *winnerFs = nil;
                if (fsNeedsRewrite && fsVariants.count > 0) {
                    for (NSHTTPCookie *c in [fsVariants reverseObjectEnumerator]) {
                        if (![c.domain hasPrefix:@"."]) { winnerFs = c.value; break; }
                    }
                    if (!winnerFs) winnerFs = fsVariants.lastObject.value;
                }

                // 1. Write canonical host-only winner FIRST (zero empty-cookie gap for concurrent fetch()!)
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
                    // 2. Delete ONLY non-canonical duplicate variants (`.linux.do` or non-`/` path), never the winning host-only entry.
                    NSMutableArray<NSHTTPCookie *> *toDelete = [NSMutableArray new];
                    if (tNeedsRewrite) {
                        for (NSHTTPCookie *c in tVariants) {
                            if ([c.domain hasPrefix:@"."] || ![c.path isEqualToString:@"/"]) {
                                [toDelete addObject:c];
                            }
                        }
                    }
                    if (fsNeedsRewrite) {
                        for (NSHTTPCookie *c in fsVariants) {
                            if ([c.domain hasPrefix:@"."] || ![c.path isEqualToString:@"/"]) {
                                [toDelete addObject:c];
                            }
                        }
                    }
                    dispatch_group_t delGrp = dispatch_group_create();
                    for (NSHTTPCookie *c in toDelete) {
                        dispatch_group_enter(delGrp);
                        [store deleteCookie:c completionHandler:^{ dispatch_group_leave(delGrp); }];
                    }
                    dispatch_group_notify(delGrp, dispatch_get_main_queue(), ^{
                        purgeSharedStorageAuthCookies(NO);
                        [store getAllCookies:^(NSArray<NSHTTPCookie *> *after) {
                            (void)after;
                            sendReply(callback, request, @{@"swept": @YES}, nil);
                        }];
                    });
                });
            }];
        } else if ([method isEqualToString:@"deleteCfClearance"]) {
            [store getAllCookies:^(NSArray<NSHTTPCookie *> *cookies) {
                NSMutableArray<NSString *> *deletedValues = [NSMutableArray array];
                dispatch_group_t group = dispatch_group_create();
                for (NSHTTPCookie *cookie in cookies) {
                    NSString *domain = [cookie.domain hasPrefix:@"."] ? [cookie.domain substringFromIndex:1] : cookie.domain;
                    if (isForumHost(domain) && isCloudflareDomainCookieName(cookie.name)) {
                        if ([cookie.name isEqualToString:@"cf_clearance"] && cookie.value.length > 0 && ![deletedValues containsObject:cookie.value]) {
                            [deletedValues addObject:cookie.value];
                        }
                        dispatch_group_enter(group);
                        [store deleteCookie:cookie completionHandler:^{ dispatch_group_leave(group); }];
                    }
                }
                // Also purge any stray Cloudflare challenge cookies in shared NSHTTPCookieStorage (100% preserving _t / _forum_session)
                NSHTTPCookieStorage *shared = [NSHTTPCookieStorage sharedHTTPCookieStorage];
                for (NSHTTPCookie *cookie in [shared.cookies copy]) {
                    NSString *domain = [cookie.domain hasPrefix:@"."] ? [cookie.domain substringFromIndex:1] : cookie.domain;
                    if (isForumHost(domain) && isCloudflareDomainCookieName(cookie.name)) {
                        [shared deleteCookie:cookie];
                    }
                }
                dispatch_group_notify(group, dispatch_get_main_queue(), ^{
                    sendReply(callback, request, @{@"deleted": deletedValues}, nil);
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
        } else if ([method isEqualToString:@"readClipboardMedia"]) {
            BOOL onlyFileUrls = [p[@"onlyFileUrls"] boolValue];
            NSPasteboard *pb = [NSPasteboard generalPasteboard];
            NSMutableArray<NSDictionary *> *items = [NSMutableArray array];
            NSString *source = @"none";
            const NSUInteger kMaxBytes = 20 * 1024 * 1024;
            const NSUInteger kMaxFiles = 10;

            NSDictionary *readOpts = @{ NSPasteboardURLReadingFileURLsOnlyKey: @YES };
            NSArray<NSURL *> *fileUrls = [pb readObjectsForClasses:@[NSURL.class] options:readOpts];
            if (fileUrls.count > 0) {
                for (NSURL *u in fileUrls) {
                    if (items.count >= kMaxFiles) break;
                    if (!u.isFileURL) continue;
                    NSDictionary *entry = lindoReadLocalMediaFile(u.path, kMaxBytes);
                    if (entry) {
                        [items addObject:entry];
                    }
                }
                if (items.count > 0) {
                    source = @"file-url";
                }
            }

            if (items.count == 0 && !onlyFileUrls) {
                NSArray<NSPasteboardType> *types = pb.types ?: @[];
                NSData *imgData = nil;
                NSString *mime = nil;
                NSString *ext = nil;

                if ([types containsObject:NSPasteboardTypePNG]) {
                    imgData = [pb dataForType:NSPasteboardTypePNG];
                    mime = @"image/png";
                    ext = @"png";
                } else if ([types containsObject:@"com.compuserve.gif"]) {
                    imgData = [pb dataForType:@"com.compuserve.gif"];
                    mime = @"image/gif";
                    ext = @"gif";
                } else if ([types containsObject:@"public.jpeg"]) {
                    imgData = [pb dataForType:@"public.jpeg"];
                    mime = @"image/jpeg";
                    ext = @"jpg";
                } else if ([types containsObject:@"org.webmproject.webp"]) {
                    imgData = [pb dataForType:@"org.webmproject.webp"];
                    mime = @"image/webp";
                    ext = @"webp";
                } else if ([types containsObject:@"public.webp"]) {
                    imgData = [pb dataForType:@"public.webp"];
                    mime = @"image/webp";
                    ext = @"webp";
                } else if ([types containsObject:NSPasteboardTypeTIFF]) {
                    NSData *tiff = [pb dataForType:NSPasteboardTypeTIFF];
                    if (tiff.length > 0 && tiff.length <= kMaxBytes * 2) {
                        imgData = lindoConvertImageDataToPng(tiff);
                        mime = @"image/png";
                        ext = @"png";
                    }
                }

                if (imgData.length > 0 && imgData.length <= kMaxBytes && mime && ext) {
                    NSDateFormatter *fmt = [[NSDateFormatter alloc] init];
                    fmt.locale = [NSLocale localeWithLocaleIdentifier:@"en_US_POSIX"];
                    fmt.dateFormat = @"yyyyMMdd-HHmmss";
                    NSString *stamp = [fmt stringFromDate:[NSDate date]] ?: @"image";
                    NSString *fname = [NSString stringWithFormat:@"paste-%@.%@", stamp, ext];
                    [items addObject:@{
                        @"filename": fname,
                        @"mime": mime,
                        @"dataBase64": [imgData base64EncodedStringWithOptions:0]
                    }];
                    source = @"image-data";
                }
            }

            sendReply(callback, request, @{ @"source": source, @"items": items }, nil);
        } else if ([method isEqualToString:@"convertLocalImageToPng"]) {
            NSString *path = [p[@"path"] isKindOfClass:NSString.class] ? p[@"path"] : @"";
            if (!path.length) {
                sendReply(callback, request, nil, @"Empty image path");
                return;
            }
            NSData *raw = [NSData dataWithContentsOfFile:path];
            if (!raw.length || raw.length > 30 * 1024 * 1024) {
                sendReply(callback, request, nil, @"Image file unreadable or too large");
                return;
            }
            NSData *png = lindoConvertImageDataToPng(raw);
            if (!png.length || png.length > 20 * 1024 * 1024) {
                sendReply(callback, request, nil, @"Failed to convert image to PNG");
                return;
            }
            sendReply(callback, request, @{
                @"dataBase64": [png base64EncodedStringWithOptions:0]
            }, nil);
        } else {
            sendReply(callback, request, nil, @"Unknown native bridge command");
        }
    });
}
