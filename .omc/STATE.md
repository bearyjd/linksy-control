# STATE (linksy-control, round 3 fixes)
Branch fix/review-3 from main e2dc09c. Not merged (later stage merges after review round 4).
Done: test commit (failing, with stubs) then fix commit; core 145 tests pass; app assembleDebug+lintDebug pass.
Defaults: owner budget burst 3000 / 1500 per s; global+Linksy bucket 200 / 200 per s; BEGIN cost 20; PinnedUid refresh 10 s; BUSY = RESULT 6.
Docs: README contract, docs/REVIEWS.md round 3, CHANGELOG updated.
Open: wait for CI on PR head SHA; round 4 review; on-device verification (nothing has run on a phone).
