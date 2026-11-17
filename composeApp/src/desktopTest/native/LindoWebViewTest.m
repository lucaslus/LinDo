// Native integration test with an isolated, nonpersistent store. No live account/network.
#import "../../desktopMain/native/LindoWebView.m"
static NSMutableDictionary *results;
static void received(long long request, const char *raw) {
    NSDictionary *value = [NSJSONSerialization JSONObjectWithData:[[NSString stringWithUTF8String:raw] dataUsingEncoding:NSUTF8StringEncoding] options:0 error:nil];
    results[@(request)] = value;
}
static NSDictionary *command(long long request, NSString *method, NSDictionary *params) {
    NSData *data = [NSJSONSerialization dataWithJSONObject:@{@"method":method,@"params":params} options:0 error:nil];
    lindo_web_command(request,[[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding].UTF8String,received);
    NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:8];
    while (!results[@(request)] && deadline.timeIntervalSinceNow>0) [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:.01]];
    NSCAssert(results[@(request)] != nil,@"Native callback timed out");
    return results[@(request)];
}
int main(void) { @autoreleasepool {
    [NSApplication sharedApplication];
    results = [NSMutableDictionary new];
    host = [[LindoWebHost alloc] initWithDataStore:WKWebsiteDataStore.nonPersistentDataStore];
    NSCAssert(!command(1,@"init",@{})[@"error"],@"init");
    NSCAssert(command(2,@"navigate",@{@"url":@"https://evil.example/"})[@"error"],@"unsafe command navigation");
    command(3,@"setCookies",@{@"cookies":@[@{@"name":@"_t",@"value":@"fixture-session"} ]});
    NSArray *cookies=command(4,@"cookies",@{})[@"value"][@"cookies"];
    NSCAssert(cookies.count==1 && [cookies[0][@"value"] isEqual:@"fixture-session"],@"cookie roundtrip");
    __block NSHTTPCookie *rawStored = nil;
    __block BOOL fetchedRaw = NO;
    [host.web.configuration.websiteDataStore.httpCookieStore getAllCookies:^(NSArray<NSHTTPCookie *> *all) {
        rawStored = all.firstObject;
        fetchedRaw = YES;
    }];
    NSDate *cookieDeadline = [NSDate dateWithTimeIntervalSinceNow:5];
    while (!fetchedRaw && cookieDeadline.timeIntervalSinceNow > 0) [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:.01]];
    NSCAssert(rawStored != nil && !rawStored.isSessionOnly && rawStored.expiresDate != nil, @"seeded cookie must carry persistent expiresDate");
    [host.web loadHTMLString:@"<html><body>Local fixture</body></html>" baseURL:nil];
    NSDate *deadline=[NSDate dateWithTimeIntervalSinceNow:8];
    while (host.web.loading && deadline.timeIntervalSinceNow>0) [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:.01]];
    NSDictionary *value=command(5,@"evaluate",@{@"script":@"Promise.resolve({answer:42})"});
    NSCAssert([value[@"value"][@"answer"] intValue]==42,@"async promise result");
    NSCAssert(command(11,@"evaluate",@{@"script":@"Promise.reject(new Error('fixture'))"})[@"error"],@"rejected promise completes with error");
    reply(received,12,[NSDate date],nil);
    NSCAssert(results[@12][@"error"],@"unsupported result completes with error instead of hanging");
    command(6,@"hide",@{});
    NSCAssert([command(7,@"cookies",@{})[@"value"][@"cookies"] count]==1,@"hide retains session");
    [host windowShouldClose:host.panel];
    NSCAssert([command(8,@"status",@{})[@"value"][@"cancelled"] boolValue],@"cancel reported");
    host.cancelled = NO;
    [host.panel cancelOperation:nil];
    NSCAssert([command(13,@"status",@{})[@"value"][@"cancelled"] boolValue],@"escape cancelOperation reported");
    command(9,@"clearCookies",@{});
    NSCAssert([command(10,@"cookies",@{})[@"value"][@"cookies"] count]==0,@"logout clears cookies");
    puts("PASS: origin guard, cookie roundtrip, async evaluation, rejected/unsupported result, retained session, cancellation, logout");
    return 0;
} }
