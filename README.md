# مؤشر ليبيا — server crawler

Every 10 minutes, GitHub Actions reads the public source channels with the same parser as the app and writes the
shared price board (`latest/*`, `ticks/*`) and caches (`cache/*`, `mkt/*`) and the news list + articles (`cache/news`, `cache/news_{id}`) to Firestore with a service account.

* No secret and no data source is stored in this repository. Secrets (Settings → Secrets → Actions): `FIREBASE_SA_JSON` and `SOURCES_JSON` (the source list, masked in logs).
* Manual run: Actions → crawler → Run workflow (dry run is on by default; untick it to write).
* Health: Firestore `config/crawler.at` is updated on every successful run.
