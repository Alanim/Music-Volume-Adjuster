package com.musicvolumeadjuster;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class MusicVolumeAdjusterPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(MusicVolumeAdjusterPlugin.class);
		RuneLite.main(args);
	}
}
