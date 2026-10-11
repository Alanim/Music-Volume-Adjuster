package com.musicvolumeadjuster;

/**
 * The four sets of track volume adjustments a person can switch between. SUGGESTED ships with a
 * curated set of volumes (bundled in suggested.json) and is read-only, so it always stays as
 * shipped; the three USER slots are freely editable and can be filled from it with a copy.
 */
public enum Profile
{
	SUGGESTED("Suggested Settings"),
	USER_1("User Settings 1"),
	USER_2("User Settings 2"),
	USER_3("User Settings 3");

	private final String label;

	Profile(String label)
	{
		this.label = label;
	}

	/** True for the built-in profile that can't be edited, only copied from. */
	public boolean isReadOnly()
	{
		return this == SUGGESTED;
	}

	/** RuneLite's config dropdown displays an enum's toString(). */
	@Override
	public String toString()
	{
		return label;
	}
}
