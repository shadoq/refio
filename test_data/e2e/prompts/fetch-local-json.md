A local HTTP server is running and serving a JSON document.

Using an HTTP request, fetch the JSON at {{FIXTURE_SERVER}}/data.json. Read the value of
its `city` field, and write just that value into a new file named `result.txt` in the
project root.

For example, if the JSON were `{"city": "Berlin"}`, then `result.txt` should contain
`Berlin`.
