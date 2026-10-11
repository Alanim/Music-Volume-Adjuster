package com.musicvolumeadjuster;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

/**
 * Shown under this plugin's cog in the RuneLite plugin list. Per-track volumes themselves are
 * edited in the sidebar panel, not here — this only holds things that make sense as plugin-wide
 * settings: which profile is active, a reset action for the active one, and the tools for copying
 * volumes between profiles. The action items are checkboxes that act as momentary buttons: they
 * show a confirmation (their warning), run once when confirmed, then flip themselves back off.
 */
@ConfigGroup(TrackVolumes.CONFIG_GROUP)
public interface MusicVolumeAdjusterConfig extends Config
{
	@ConfigItem(
		keyName = "profile",
		name = "Settings profile",
		description = "Switches between the built-in read-only Suggested Settings and three separate editable "
				+ "sets of song volume adjustments (User Settings 1-3).",
		position = 0
	)
	default Profile profile()
	{
		return Profile.USER_1;
	}

	@ConfigItem(
		keyName = "resetToDefault",
		name = "Reset current profile",
		description = "Clears every song volume adjustment in the current profile back to 1.00. "
			+ "Suggested Settings is read-only and is never changed.",
		warning = "This clears every song volume adjustment in the CURRENT profile back to 1.00. "
			+ "This can't be undone. (Suggested Settings is read-only and is not affected.) Continue?",
		position = 1
	)
	default boolean resetToDefault()
	{
		return false;
	}

	@ConfigItem(
		keyName = "importSuggested",
		name = "Import suggested volumes",
		description = "Replaces every song volume adjustment in the current profile with the Suggested Settings. "
			+ "Does nothing while Suggested Settings itself is the current profile.",
		warning = "This REPLACES every song volume adjustment in the CURRENT profile with the Suggested Settings. "
			+ "This can't be undone. Continue?",
		position = 2
	)
	default boolean importSuggested()
	{
		return false;
	}

	@ConfigItem(
		keyName = "copyTarget",
		name = "Copy current profile to",
		description = "The profile that \"Copy now\" will overwrite with a copy of the current profile.",
		position = 3
	)
	default CopyTarget copyTarget()
	{
		return CopyTarget.USER_2;
	}

	@ConfigItem(
		keyName = "copyNow",
		name = "Copy now",
		description = "Replaces every song volume adjustment in the profile chosen above with a copy of the "
			+ "current profile, so the copy can be adjusted without touching the original.",
		warning = "This REPLACES every song volume adjustment in the profile chosen under \"Copy current profile to\" "
			+ "with a copy of the CURRENT profile. This can't be undone. Continue?",
		position = 4
	)
	default boolean copyNow()
	{
		return false;
	}
}
