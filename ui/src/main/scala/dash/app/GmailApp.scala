package dash
package app

import qq.data.{ConcreteFilter, Program}
import qq.macros.QQInterpolator._

object GmailApp {

  val program: Program[ConcreteFilter] =
    qq"""
def authHeaders: { Authorization: "Bearer " + googleAuth };

def listUnreadThreadParams: {
  maxResults: 10,
  q: "is:unread"
};

def getThreadDetailsParams: {
  format: "metadata",
  metadataHeaders: "Subject",
  fields: "messages(payload/headers,snippet)"
};

def unreadThreadDetails:
  httpGet("https://www.googleapis.com/gmail/v1/users/me/threads"; listUnreadThreadParams; ""; authHeaders) |
    .threads[].id |
    httpGet("https://www.googleapis.com/gmail/v1/users/me/threads/" + .; getThreadDetailsParams; ""; authHeaders);

def unreadThreadDetailsToContent: {
  title: "Unread threads",
  content: [.[].messages.[0] | {
    title: .payload.headers.[0].value,
    content: .snippet
  }]
};

[unreadThreadDetails] | unreadThreadDetailsToContent
    """

}
