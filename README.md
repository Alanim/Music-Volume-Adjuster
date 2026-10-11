# Music Volume Adjuster

Applies a per-track volume multiplier on top of your normal in-game music
volume. Songs you haven't adjusted play at your normal volume. Jingles
(quest/diary/slayer-task complete, etc.) are held at your normal volume too,
since they don't belong to any one track.

## Song transitions

When a new song starts, the volume is dropped straight to 1 (0 would stop
playback) and then ramps up smoothly to the song's adjusted volume over
2 seconds, instead of jumping after a delay.

## Using the panel

Open the plugin's sidebar panel (the green "V" icon). It lists every track
in the game, alphabetically, each with a `-`, a value, and a `+`:

- Click `-` / `+` to nudge a track's multiplier by 0.01.
- Or click the value and type a number directly, then press Enter or click
  elsewhere to apply it.
- 1.00 = unchanged. 1.25 = 25% louder. 0.70 = 30% quieter. Values are
  clamped between 0.00 and 5.00.
- The search box at the top filters the list as you type.
- A "Now Playing" row above the search box always shows and controls
  whatever's currently playing — it's the same underlying value as the
  matching row in the list, just pinned to the top.
- If you change the multiplier for the song currently playing, the volume
  updates immediately, calculated from your normal (base) volume.

Everything you set is saved automatically and reloads next time you start
RuneLite.

If a song plays that isn't in the track list (for example, one added to the
game after this plugin was built), it's added to the list automatically at
1.00 and stays there until you adjust it. Newly found songs are remembered
between launches.

## Profiles

Click this plugin's gear icon in RuneLite's plugin list for these settings:

- **Settings profile** — a dropdown with four sets of track volumes:
  *Suggested Settings*, *User Settings 1*, *2*, and *3*. Switching profiles
  immediately updates the sidebar panel and whatever's currently playing.
  - *Suggested Settings* comes with a curated set of volumes and is
    **read-only**, so it always stays as shipped.
  - The three *User Settings* are fully editable.
- **Reset current profile** — a checkbox that resets every track in the
  *current* user profile back to 1.00. Suggested Settings is never changed.
- **Import suggested volumes** — a checkbox that replaces every volume in
  the current user profile with the Suggested Settings, so you can start
  from the suggested values and tweak them without touching the originals.
- **Copy current profile to** and **Copy now** — pick a user slot in the
  dropdown, then tick *Copy now* to replace every volume in that slot with
  the current profile's volumes. With Suggested Settings active, this copies
  the suggested volumes into any user slot.

The checkboxes act as buttons: each asks for confirmation (since they
overwrite or clear a profile), does its job, then unticks itself. The result
of a copy or import is shown under the profile name in the sidebar panel.

## Notes

- Volume is capped at the game's max (255). If your in-game slider is
  already at max, boosting a quiet song has no headroom — keep your base
  volume below max so quiet songs have room to go up.
- Settings saved by earlier versions (when this plugin was called Music
  Volume Equalizer) are migrated automatically.

## License

BSD 2-Clause, see `LICENSE`.
