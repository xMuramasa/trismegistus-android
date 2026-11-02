# Protocol copy

`schema.json` and `examples/` are copied from `xMuramasa/trismegistus` (`protocol/`, commit `48c3c2a`). That repo
owns the protocol; this copy exists so the `:core` module's contract tests run without a checkout of the other repo.
`scripts/sync-protocol.sh` refreshes it and a test fails when the two differ in sha256 (`protocol/SOURCE`, written by
`scripts/protocol-hash.sh`).
