package com.musicvolumeadjuster;

/**
 * The profiles another profile can be copied into: the three editable user slots. Suggested
 * Settings is deliberately absent, since it's read-only and can only be copied from.
 */
public enum CopyTarget
{
	USER_1("User Settings 1", Profile.USER_1),
	USER_2("User Settings 2", Profile.USER_2),
	USER_3("User Settings 3", Profile.USER_3);

	private final String label;
	private final Profile profile;

	CopyTarget(String label, Profile profile)
	{
		this.label = label;
		this.profile = profile;
	}

	Profile toProfile()
	{
		return profile;
	}

	/** RuneLite's config dropdown displays an enum's toString(). */
	@Override
	public String toString()
	{
		return label;
	}
}
