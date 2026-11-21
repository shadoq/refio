`config/config.go` is the key=value loader the ingest jobs depend on. It works, but its
error handling is stuck in an older style: two spots flatten the underlying error with
`fmt.Errorf("%s", err)`, and the "missing required key" / "port out of range" failures are
plain string errors that callers can only recognize by substring matching. Two callers now
need to branch on *why* a load failed, so the errors have to become inspectable.

Your task:

1. Introduce exported sentinel errors in `config/config.go`: `ErrConfigMissing` for an absent
   required key and `ErrInvalidValue` for a key whose value fails validation.
2. Wrap every error that `Load` or its helpers return or propagate with `fmt.Errorf` using
   the `%w` verb - including the `os.ReadFile` failure and the `strconv.Atoi` failure - so
   the full error chain survives and the sentinels stay reachable.
3. Add an exported helper `IsNotFound(err error) bool` that reports whether a load failure
   was caused by a missing key, implemented with `errors.Is`.
4. Keep the accepted file format, the `Load(path string) (map[string]string, error)`
   signature and the validation rules exactly as they are - only the error style changes.

Do not modify `go.mod` or the README: the change belongs in `config/config.go`.
