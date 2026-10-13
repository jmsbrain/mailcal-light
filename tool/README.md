# Mailcal Light

A mailbox.org calendar for the Light Phone III: see your agenda or a month view, add, edit and delete events. Black and white, styled like LightOS.

Built as a Light SDK tool on a copy of [lightphone/light-sdk](https://github.com/lightphone/light-sdk); Mailcal is the `tool` module.

## How it works

Mailcal talks to mailbox.org directly over CalDAV. It does **not** use DAVx5 or the phone's built-in calendar, so there's no sync between the two that can break or reset.

- The calendar on mailbox.org is the only real copy. Edits go straight to the server and are refused if the event changed elsewhere first, so nothing gets silently overwritten.
- The app keeps an offline copy (30 days back, a year ahead) so the agenda opens without signal. Changes need a connection.
- The copy refreshes when you open the app and in the background about every 30 minutes (the phone may space this out when idle). Only calendars that changed are downloaded.
- If the offline copy is ever lost, the app just downloads it again. Nothing on mailbox.org is touched.
- Editing a repeating event asks whether to change **this one** or **all**. Edits change only the fields shown and keep everything else in the event (attendees and so on).

## Feeds

Mailcal can also show subscribed .ics feeds, read-only. Switch between **MAILBOX** and **FEEDS** by tapping the tabs under the top bar, or in month view by tapping the selected day again. Add feeds from MENU → FEEDS by typing or pasting a link; Google share links work for public calendars. Feeds refresh hourly, when the app opens and in the background.

## Month view

Tap MONTH on the agenda for a month grid. Days with events get a dot per event (up to three). Tap a day to list its events under the grid; PREV/NEXT change months. NEW starts on the selected day. LIST goes back, and the app remembers which view you used last.

## Reminders

Pick a reminder when adding or editing an event: at the time of the event, 30 minutes, 1 hour, 12 hours, 1 day, 3 days, 1 week or 2 weeks before. It's saved in the event on mailbox.org, so your other devices remind you.

**This phone doesn't show reminders yet.** Light SDK tools can't set alarms or post notifications so far. Events with several reminders, or reminders at other times, show "Custom" and are left as they are unless you pick a new one.

## Signing in

Use your mailbox.org email and password. If you use two-factor sign-in, make an app password in mailbox.org settings and use that instead. The server defaults to `https://dav.mailbox.org/`; any CalDAV server over https should work.

The password is kept on the phone, encrypted with a key in the Android Keystore, and only sent to that server. (The LightOS text editor shows it while you type it.)

## Permissions

- Internet, to reach the CalDAV server and any feeds you add. Nothing else leaves the phone.

## Alongside the Android Mailcal

Mailcal Light is a separate app (`com.jmsbrain.mailcal.light`), so it installs next to the sideloaded [Mailcal](https://github.com/jmsbrain/mailcal) rather than replacing it. Sign in and add feeds again; your calendar is on mailbox.org, so both see the same events.

## License

MIT
