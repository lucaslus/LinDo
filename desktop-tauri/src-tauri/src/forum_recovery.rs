use serde_json::Value;
use url::Url;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ReadResult {
    Ready,
    SiteError,
    Challenge,
    Unavailable,
}

impl ReadResult {
    pub fn reached_forum(self) -> bool {
        matches!(self, Self::Ready | Self::SiteError)
    }
}

/// Only a user-requested reconnect may display the original forum page. A second read
/// is permitted after the person completes that interaction; it cannot reopen the panel.
pub async fn retry_forum_read<R, C>(
    explicit_retry: bool,
    mut read: impl FnMut() -> R,
    confirm: impl FnOnce() -> C,
) -> (ReadResult, Value)
where
    R: std::future::Future<Output = (ReadResult, Value)>,
    C: std::future::Future<Output = bool>,
{
    let first = read().await;
    if explicit_retry
        && (first.0 == ReadResult::Challenge || first.1["originLost"] == true)
        && confirm().await
    {
        return read().await;
    }
    first
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Kind {
    Topic,
    Feed,
}

/// Only ordinary topic and feed reads may be used to confirm that browsing works.
#[derive(Clone, Debug)]
pub struct ForumReadTarget {
    pub url: Url,
    kind: Kind,
}

impl ForumReadTarget {
    pub fn from_request(method: &str, raw_url: &str) -> Option<Self> {
        if method != "GET" {
            return None;
        }
        let url = Url::parse(raw_url).ok()?;
        if url.origin().ascii_serialization() != "https://linux.do" {
            return None;
        }
        let path = url.path().strip_suffix(".json")?;
        let parts: Vec<_> = path.trim_start_matches('/').split('/').collect();
        let number = |s: &str| !s.is_empty() && s.bytes().all(|c| c.is_ascii_digit());
        let kind = match parts.as_slice() {
            ["t", id] if number(id) => Kind::Topic,
            ["t", id, floor] if number(id) && number(floor) => Kind::Topic,
            [.., "latest" | "top" | "new" | "unread" | "hot"]
                if parts.len() == 1 || matches!(parts[0], "c" | "tags" | "tag") =>
            {
                Kind::Feed
            }
            ["c", .., id] if number(id) => Kind::Feed,
            ["tag", _] | ["tags", "c", .., _] => Kind::Feed,
            _ => return None,
        };
        Some(Self { url, kind })
    }

    pub fn latest() -> Self {
        Self::from_request("GET", "https://linux.do/latest.json?no_definitions=true").unwrap()
    }

    pub fn label(&self) -> &'static str {
        match self.kind {
            Kind::Topic => "topic",
            Kind::Feed => "feed",
        }
    }

    pub fn page_url(&self) -> Url {
        let mut page = self.url.clone();
        page.set_path(self.url.path().trim_end_matches(".json"));
        page.set_query(None);
        page
    }

    pub fn classify(&self, reply: &Value) -> ReadResult {
        if reply["challenge"]
            .as_str()
            .is_some_and(|s| s.eq_ignore_ascii_case("challenge"))
        {
            return ReadResult::Challenge;
        }
        let status = reply["status"].as_u64().unwrap_or(0);
        let content_type = reply["contentType"]
            .as_str()
            .unwrap_or("")
            .split(';')
            .next()
            .unwrap_or("")
            .trim();
        if !content_type.eq_ignore_ascii_case("application/json") {
            return ReadResult::Unavailable;
        }
        let body = reply["body"]
            .as_str()
            .and_then(|s| serde_json::from_str::<Value>(s).ok());
        let Some(body) = body else {
            return ReadResult::Unavailable;
        };
        if matches!(status, 401 | 403 | 404 | 422 | 429)
            && (body["errors"].is_array() || body["error_type"].is_string())
        {
            return ReadResult::SiteError;
        }
        if !(200..300).contains(&status) {
            return ReadResult::Unavailable;
        }
        let valid = match self.kind {
            Kind::Topic => {
                let id = self
                    .url
                    .path()
                    .split('/')
                    .nth(2)
                    .and_then(|s| s.trim_end_matches(".json").parse::<u64>().ok());
                id.is_some() && body["id"].as_u64() == id && body["post_stream"]["posts"].is_array()
            }
            Kind::Feed => body["topic_list"]["topics"].is_array(),
        };
        if valid {
            ReadResult::Ready
        } else {
            ReadResult::Unavailable
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::{cell::Cell, future::ready};

    #[test]
    fn only_recognizes_the_requested_topic_or_feed_and_preserves_real_site_errors() {
        let topic = ForumReadTarget::from_request(
            "GET",
            "https://linux.do/t/482293/8.json?track_visit=false",
        )
        .unwrap();
        assert_eq!(topic.page_url().as_str(), "https://linux.do/t/482293/8");
        let reply = |status, body: Value| json!({ "status": status, "contentType": "application/json; charset=utf-8", "body": body.to_string() });
        let post = json!({ "id": 482293, "post_stream": { "posts": [] }});
        assert_eq!(topic.classify(&reply(200u16, post)), ReadResult::Ready);
        assert_eq!(
            topic.classify(&reply(
                200,
                json!({ "id": 42, "post_stream": { "posts": [] }})
            )),
            ReadResult::Unavailable
        );
        let feed = ForumReadTarget::latest();
        assert_eq!(
            feed.classify(&reply(200, json!({ "topic_list": { "topics": [] }}))),
            ReadResult::Ready
        );
        assert_eq!(
            feed.classify(
                &json!({ "status": 200, "contentType": "text/html", "body": "<html></html>" })
            ),
            ReadResult::Unavailable
        );
        assert_eq!(
            feed.classify(&reply(200, json!({ "errors": [] }))),
            ReadResult::Unavailable
        );
        for status in [401, 403, 404, 429] {
            let err = reply(status, json!({ "errors": ["forum error"] }));
            assert_eq!(feed.classify(&err), ReadResult::SiteError);
            let mut challenge = err;
            challenge["challenge"] = json!("challenge");
            assert_eq!(feed.classify(&challenge), ReadResult::Challenge);
        }
        assert_eq!(
            feed.classify(&reply(503, json!({ "errors": ["unavailable"] }))),
            ReadResult::Unavailable
        );
    }

    #[test]
    fn replay_is_limited_to_first_party_topic_and_feed_reads() {
        for (method, url) in [
            ("POST", "https://linux.do/t/482293.json"),
            ("GET", "https://example.com/latest.json"),
            ("GET", "https://linux.do/session/current.json"),
            ("GET", "https://linux.do/about"),
            ("GET", "https://linux.do/t/482293/invite.json"),
        ] {
            assert!(ForumReadTarget::from_request(method, url).is_none());
        }
        for path in [
            "/c/42.json",
            "/tags/c/42/project.json",
            "/tag/project/l/top.json",
            "/unread.json?page=2",
        ] {
            assert!(
                ForumReadTarget::from_request("GET", &format!("https://linux.do{path}")).is_some()
            );
        }
    }

    #[tokio::test]
    async fn repeated_challenge_gets_one_read_and_only_an_explicit_retry_may_open_one_window() {
        for (explicit, completed, expected_reads, expected_windows) in [
            (false, false, 1, 0),
            (true, false, 1, 1),
            (true, true, 2, 1),
        ] {
            let reads = Cell::new(0);
            let windows = Cell::new(0);
            let (outcome, _) = retry_forum_read(
                explicit,
                || {
                    reads.set(reads.get() + 1);
                    ready((ReadResult::Challenge, json!({ "status": 403 })))
                },
                || {
                    windows.set(windows.get() + 1);
                    ready(completed)
                },
            )
            .await;
            assert_eq!(outcome, ReadResult::Challenge);
            assert_eq!(
                (reads.get(), windows.get()),
                (expected_reads, expected_windows)
            );
        }
        for outcome in [
            ReadResult::Ready,
            ReadResult::SiteError,
            ReadResult::Unavailable,
        ] {
            let (result, _) = retry_forum_read(
                true,
                || ready((outcome, Value::Null)),
                || async { panic!("ordinary response or timeout must not open verification") },
            )
            .await;
            assert_eq!(result, outcome);
        }
    }
}
