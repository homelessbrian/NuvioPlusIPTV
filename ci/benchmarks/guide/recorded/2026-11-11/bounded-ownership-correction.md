# Correction to the early bounded-ownership claims

The frozen `bounded-df9f5146` record says that source guards/tests prove the model ownership contract. That wording was too broad. Its counters validate registered owners and reservations; they do not independently establish that every retained UI programme object is registered.

A subsequent source audit found two gaps in `df9f5146` and `07672516`: structural-equality UI memos can retain equal programmes from a retired publication, and open menu/dialog selections can intentionally retain a changed or removed programme. Such objects can outlive their original paid owner. This is a correctness finding, not an attribution of the measured heap peaks. The fixtures have no selected Home channels, so the Home projection case does not explain their observations.

The numeric records, original CSV bytes, input proofs, exported file manifests and adverse RAM results remain unchanged. An estimated 8 MiB registered-model limit never represented a whole-app Java/native/graphics/process memory limit. Closing the ownership gaps requires separate lifecycle/source validation and fresh measurements for the repaired source; the early counters alone cannot establish it.
