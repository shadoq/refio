Add a new endpoint to the toy router in `src/router.py`: path `/status`, returning the
string `ok`. Register it the same way the existing `/health` and `/version` endpoints are
registered. The test suite already covers the new endpoint; when you are done,
`python3 -m unittest test_router` must pass. Do not edit `test_router.py`.
