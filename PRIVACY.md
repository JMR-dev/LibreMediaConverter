# Privacy Policy

**LibreMediaConverter does not collect any data.**

That is the whole policy, but here is what it means concretely.

## No data leaves your device

The app has **no network access**. It does not declare the `INTERNET` permission, so it
is not merely a promise not to transmit anything — the operating system will not let it.
Your files are converted on your device and stay there.

## No analytics, advertising or tracking

There is no analytics SDK, no crash reporter, no advertising identifier, and no
third-party service of any kind.

## What the app accesses, and why

| Access | Why |
|---|---|
| Files you explicitly pick | Read as conversion input. The app uses the system file picker and can only see files you choose. It never scans your storage. |
| The destination you choose for output | Write the converted file. Again, only where you point it. |
| Notifications | Show conversion progress so you can leave the app while a long job runs. Optional; conversions work without it. |

The app does not request storage permissions. It uses the Storage Access Framework,
which grants access only to the individual files you select.

## The full permission list

Inspecting the app will show a few permissions that are not in the table above. They are
added automatically by the Jetpack WorkManager library, which runs conversions in the
background. Listing them here rather than leaving you to wonder:

| Permission | Origin | What it does here |
|---|---|---|
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROCESSING`, `FOREGROUND_SERVICE_DATA_SYNC` | Ours | Keep a conversion running when the app is not in the foreground. Android requires a declared service type for this. |
| `POST_NOTIFICATIONS` | Ours | Show conversion progress. Optional. |
| `WAKE_LOCK` | WorkManager | Stop the device sleeping mid-conversion. |
| `RECEIVE_BOOT_COMPLETED` | WorkManager | Restore an unfinished job queue after a restart. |
| `ACCESS_NETWORK_STATE` | WorkManager | WorkManager can gate jobs on connectivity. **This app does not use that feature**, and the permission only allows reading whether a network exists — it does not permit any network communication. |

Notably absent is `INTERNET`. Without it the operating system will not allow the app to
open a network connection at all, so "your files stay on your device" is enforced by
Android rather than resting on our word.

## Where files are stored

Conversions are written to the app's private cache while they run, then copied to the
location you choose. The temporary copy is deleted afterwards. Uninstalling the app
removes everything in its private storage.

## Contact

Report issues at the project's repository.
