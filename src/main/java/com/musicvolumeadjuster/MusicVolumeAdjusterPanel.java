package com.musicvolumeadjuster;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar panel listing every track in the game, alphabetically, each with a multiplier that can
 * be nudged with +/- buttons (0.01 per click) or typed directly. A search box filters the list.
 *
 * A "Now Playing" row is pinned above the search box and always tracks whatever's currently
 * playing (set by the plugin via {@link #setNowPlayingTrack}). It shares the same underlying
 * multiplier as the matching row further down the list: changing either one updates the other,
 * and both apply live if that track is playing right now. The list itself is never reordered.
 *
 * While the read-only Suggested profile is active, every row is disabled. Switching, copying and
 * resetting profiles all happen from the plugin's settings (gear icon); this panel just shows the
 * active profile and the outcome of the last copy/import via {@link #showStatus}.
 */
class MusicVolumeAdjusterPanel extends PluginPanel
{
	private static final double STEP = 0.01;

	private final TrackVolumes trackVolumes;
	private final MusicVolumeAdjusterPlugin plugin;
	private final RowContainer rowContainer = new RowContainer();
	private final List<TrackRow> rows = new ArrayList<>();
	private final Map<String, TrackRow> rowsByNormalizedName = new HashMap<>();
	private final NowPlayingRow nowPlayingRow;
	private final JLabel profileLabel;
	private final JLabel statusLabel;
	private final JTextField search;

	MusicVolumeAdjusterPanel(TrackVolumes trackVolumes, MusicVolumeAdjusterPlugin plugin)
	{
		super(false);
		this.trackVolumes = trackVolumes;
		this.plugin = plugin;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(10, 10, 10, 10));

		JLabel title = new JLabel("Music Volume Adjuster");
		title.setForeground(Color.WHITE);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setBorder(new EmptyBorder(0, 0, 2, 0));

		profileLabel = new JLabel();
		profileLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		profileLabel.setFont(FontManager.getRunescapeSmallFont());
		profileLabel.setToolTipText("Switch, copy or reset profiles from this plugin's gear icon in the plugin list");
		profileLabel.setBorder(new EmptyBorder(0, 0, 2, 0));

		statusLabel = new JLabel(" ");
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setBorder(new EmptyBorder(0, 0, 8, 0));

		JLabel nowPlayingHeader = new JLabel("NOW PLAYING");
		nowPlayingHeader.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		nowPlayingHeader.setFont(FontManager.getRunescapeSmallFont());
		nowPlayingHeader.setBorder(new EmptyBorder(0, 2, 2, 0));

		nowPlayingRow = new NowPlayingRow();

		JPanel nowPlayingSection = new JPanel(new BorderLayout());
		nowPlayingSection.setOpaque(false);
		nowPlayingSection.setBorder(new EmptyBorder(0, 0, 10, 0));
		nowPlayingSection.add(nowPlayingHeader, BorderLayout.NORTH);
		nowPlayingSection.add(nowPlayingRow.component, BorderLayout.CENTER);

		search = new JTextField();
		search.setToolTipText("Search tracks");
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				filter(search.getText());
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				filter(search.getText());
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				filter(search.getText());
			}
		});

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.add(title);
		top.add(profileLabel);
		top.add(statusLabel);
		top.add(nowPlayingSection);
		top.add(search);

		rowContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

		for (String name : trackVolumes.getAllTrackNames())
		{
			TrackRow row = new TrackRow(name);
			rows.add(row);
			rowsByNormalizedName.put(TrackVolumes.normalize(name), row);
			rowContainer.add(row.component);
		}

		JScrollPane scroll = new JScrollPane(rowContainer);
		scroll.setBorder(null);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(top, BorderLayout.NORTH);
		add(scroll, BorderLayout.CENTER);

		updateProfileControls();
	}

	/**
	 * Called by the plugin whenever the playing track changes. Safe to call from any thread; an
	 * empty or null name clears the row (nothing playing / logged out).
	 */
	void setNowPlayingTrack(String displayName)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> setNowPlayingTrack(displayName));
			return;
		}
		nowPlayingRow.bind(displayName == null ? "" : displayName);
	}

	/**
	 * Adds a row for a song that wasn't in the track list (the plugin found it playing), in its
	 * alphabetical place. Safe to call from any thread; does nothing if the row already exists.
	 */
	void addTrack(String name)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> addTrack(name));
			return;
		}

		String norm = TrackVolumes.normalize(name);
		if (rowsByNormalizedName.containsKey(norm))
		{
			return;
		}

		TrackRow row = new TrackRow(name);
		row.setEditable(!trackVolumes.getCurrentProfile().isReadOnly());

		int index = 0;
		while (index < rows.size() && TrackVolumes.TRACK_ORDER.compare(rows.get(index).name, name) <= 0)
		{
			index++;
		}
		rows.add(index, row);
		rowsByNormalizedName.put(norm, row);
		rowContainer.add(row.component, index);

		filter(search.getText());
	}

	/**
	 * Called by the plugin after the active profile changes or is reset, so every displayed value
	 * (the full list and the Now Playing row) reflects the new profile's stored multipliers.
	 */
	void refreshAllMultipliers()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::refreshAllMultipliers);
			return;
		}

		for (TrackRow row : rows)
		{
			row.refreshSilently(trackVolumes.getMultiplier(TrackVolumes.normalize(row.name)));
		}
		if (nowPlayingRow.boundNormalizedName != null)
		{
			nowPlayingRow.refreshSilently(trackVolumes.getMultiplier(nowPlayingRow.boundNormalizedName));
		}
		updateProfileControls();
	}

	/**
	 * Brings everything that depends on the active profile up to date: the label, and whether the
	 * rows are editable (not for the read-only Suggested Settings). Must run on the EDT.
	 */
	private void updateProfileControls()
	{
		Profile current = trackVolumes.getCurrentProfile();
		boolean readOnly = current.isReadOnly();

		profileLabel.setText("Profile: " + current + (readOnly ? " (read-only)" : ""));

		for (TrackRow row : rows)
		{
			row.setEditable(!readOnly);
		}
		nowPlayingRow.setProfileEditable(!readOnly);
	}

	/** Shows a short result message (e.g. "Copied to User Settings 2") under the profile name. */
	void showStatus(String text)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> showStatus(text));
			return;
		}
		statusLabel.setText(text);
	}

	private void filter(String query)
	{
		String q = query.trim().toLowerCase();
		for (TrackRow row : rows)
		{
			row.component.setVisible(q.isEmpty() || row.lowerName.contains(q));
		}
		rowContainer.revalidate();
		rowContainer.repaint();
	}

	private static double parseOrFallback(String text, double fallback)
	{
		try
		{
			return Double.parseDouble(text.trim());
		}
		catch (NumberFormatException e)
		{
			return fallback;
		}
	}

	private static String format(double value)
	{
		return String.format("%.2f", value);
	}

	private static JButton smallButton(String text)
	{
		JButton b = new JButton(text);
		b.setMargin(new Insets(0, 4, 0, 4));
		b.setFocusable(false);
		return b;
	}

	/**
	 * Holds the track rows and forces its width to always match the viewport (the sidebar's
	 * actual visible width), regardless of how wide a row's content wants to be. Without this,
	 * a long track name can make the whole list wider than the sidebar, pushing the +/- controls
	 * off the visible edge instead of just letting the name clip.
	 */
	private static class RowContainer extends JPanel implements Scrollable
	{
		RowContainer()
		{
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return visibleRect.height;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	/** One row in the full alphabetical list, permanently bound to a single track. */
	private class TrackRow
	{
		final String name;
		final String lowerName;
		final JPanel component;
		final JTextField field;
		final JButton minus;
		final JButton plus;

		TrackRow(String name)
		{
			this.name = name;
			this.lowerName = name.toLowerCase();

			JLabel label = new JLabel(name);
			label.setForeground(Color.LIGHT_GRAY);
			label.setFont(FontManager.getRunescapeSmallFont());
			label.setToolTipText(name); // full name on hover, in case it's clipped for width

			double initial = trackVolumes.getMultiplier(TrackVolumes.normalize(name));
			field = new JTextField(format(initial));
			field.setHorizontalAlignment(SwingConstants.CENTER);
			field.setColumns(4);

			minus = smallButton("\u2212");
			plus = smallButton("+");
			minus.addActionListener(e -> adjust(-STEP));
			plus.addActionListener(e -> adjust(STEP));

			// Commit on Enter and when the field loses focus, so keyboard input works too
			field.addActionListener(e -> commitFromField());
			field.addFocusListener(new FocusAdapter()
			{
				@Override
				public void focusLost(FocusEvent e)
				{
					commitFromField();
				}
			});

			JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
			controls.setOpaque(false);
			controls.add(minus);
			controls.add(field);
			controls.add(plus);

			component = new JPanel(new BorderLayout());
			component.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			component.setBorder(new EmptyBorder(4, 6, 4, 6));
			component.add(label, BorderLayout.CENTER);
			component.add(controls, BorderLayout.EAST);
			component.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
			component.setAlignmentX(0f);
		}

		/** Updates the displayed value only, without writing to storage or re-notifying anyone. */
		void refreshSilently(double value)
		{
			field.setText(format(value));
		}

		void setEditable(boolean editable)
		{
			field.setEnabled(editable);
			minus.setEnabled(editable);
			plus.setEnabled(editable);
		}

		private void adjust(double delta)
		{
			set(parseOrFallback(field.getText(), currentValue()) + delta);
		}

		private void commitFromField()
		{
			set(parseOrFallback(field.getText(), currentValue()));
		}

		private double currentValue()
		{
			return trackVolumes.getMultiplier(TrackVolumes.normalize(name));
		}

		private void set(double value)
		{
			if (trackVolumes.getCurrentProfile().isReadOnly())
			{
				return;
			}
			value = TrackVolumes.clamp(value);
			field.setText(format(value));
			trackVolumes.setMultiplier(name, value);
			plugin.onMultiplierChanged(name);

			// Keep the pinned Now Playing row in sync if it's showing this same track
			if (TrackVolumes.normalize(name).equals(nowPlayingRow.boundNormalizedName))
			{
				nowPlayingRow.refreshSilently(value);
			}
		}
	}

	/**
	 * The pinned row above the search box. Its bound track changes as songs change; editing it
	 * writes through to the same storage as the matching row in the list below, and keeps that
	 * row visually in sync.
	 */
	private class NowPlayingRow
	{
		String boundDisplayName = "";
		String boundNormalizedName = null;
		boolean profileEditable = true;

		final JLabel nameLabel;
		final JTextField field;
		final JButton minus;
		final JButton plus;
		final JPanel component;

		NowPlayingRow()
		{
			nameLabel = new JLabel("Nothing playing");
			nameLabel.setForeground(Color.WHITE);
			nameLabel.setFont(FontManager.getRunescapeSmallFont());

			field = new JTextField(format(1.0));
			field.setHorizontalAlignment(SwingConstants.CENTER);
			field.setColumns(4);
			field.setEnabled(false);

			minus = smallButton("\u2212");
			plus = smallButton("+");
			minus.setEnabled(false);
			plus.setEnabled(false);
			minus.addActionListener(e -> adjust(-STEP));
			plus.addActionListener(e -> adjust(STEP));

			field.addActionListener(e -> commitFromField());
			field.addFocusListener(new FocusAdapter()
			{
				@Override
				public void focusLost(FocusEvent e)
				{
					commitFromField();
				}
			});

			JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
			controls.setOpaque(false);
			controls.add(minus);
			controls.add(field);
			controls.add(plus);

			component = new JPanel(new BorderLayout());
			component.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			component.setBorder(new EmptyBorder(6, 6, 6, 6));
			component.add(nameLabel, BorderLayout.CENTER);
			component.add(controls, BorderLayout.EAST);
		}

		void bind(String displayName)
		{
			boundDisplayName = displayName;
			boolean has = !displayName.isEmpty();
			boundNormalizedName = has ? TrackVolumes.normalize(displayName) : null;

			nameLabel.setText(has ? displayName : "Nothing playing");
			nameLabel.setToolTipText(has ? displayName : null);
			updateEnabled();

			if (has)
			{
				field.setText(format(trackVolumes.getMultiplier(boundNormalizedName)));
			}
		}

		/** Called when the active profile changes between editable and read-only. */
		void setProfileEditable(boolean editable)
		{
			profileEditable = editable;
			updateEnabled();
		}

		private void updateEnabled()
		{
			boolean enabled = boundNormalizedName != null && profileEditable;
			field.setEnabled(enabled);
			minus.setEnabled(enabled);
			plus.setEnabled(enabled);
		}

		void refreshSilently(double value)
		{
			field.setText(format(value));
		}

		private void adjust(double delta)
		{
			set(parseOrFallback(field.getText(), currentValue()) + delta);
		}

		private void commitFromField()
		{
			set(parseOrFallback(field.getText(), currentValue()));
		}

		private double currentValue()
		{
			return boundNormalizedName == null ? 1.0 : trackVolumes.getMultiplier(boundNormalizedName);
		}

		private void set(double value)
		{
			if (boundNormalizedName == null || !profileEditable)
			{
				return;
			}
			value = TrackVolumes.clamp(value);
			field.setText(format(value));
			trackVolumes.setMultiplier(boundDisplayName, value);
			plugin.onMultiplierChanged(boundDisplayName);

			TrackRow match = rowsByNormalizedName.get(boundNormalizedName);
			if (match != null)
			{
				match.refreshSilently(value);
			}
		}
	}
}
