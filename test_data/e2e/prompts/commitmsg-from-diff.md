Write the commit message for this staged diff. Style: a short imperative subject line (72 characters at most, no type prefix, no trailing period), a blank line, then a body of a few sentences saying what changed in behaviour and why. Wrap every line at 72 characters. Reply with the commit message only, nothing else.

```diff
diff --git a/ratelimit/bucket.py b/ratelimit/bucket.py
--- a/ratelimit/bucket.py
+++ b/ratelimit/bucket.py
@@ -1,3 +1,4 @@
+import math
 import time


@@ -24,8 +25,10 @@ class TokenBucket:
         self.tokens -= 1
         return True

-    def retry_after(self):
-        return 60
+    def retry_after(self):
+        """Whole seconds until one token is available again, at least 1."""
+        missing = 1 - self.tokens
+        return max(1, math.ceil(missing / self.rate))

 def _refill(self, now):
     elapsed = now - self.updated
diff --git a/ratelimit/middleware.py b/ratelimit/middleware.py
--- a/ratelimit/middleware.py
+++ b/ratelimit/middleware.py
@@ -18,7 +18,7 @@ def limit(handler, buckets):
         bucket = buckets.for_client(request.client_id)
         if not bucket.try_take():
             response = Response(status=429)
-            response.headers["Retry-After"] = "60"
+            response.headers["Retry-After"] = str(bucket.retry_after())
             return response
         return handler(request)
diff --git a/ratelimit/handlers.py b/ratelimit/handlers.py
--- a/ratelimit/handlers.py
+++ b/ratelimit/handlers.py
@@ -1,6 +1,6 @@
-import json
-import logging
+import logging
+import json

-# we recieve the payload as bytes
+# we receive the payload as bytes
 def parse(body):
     return json.loads(body)
```
