/*
 * Copyright 2017 Derek Weber
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package au.org.dcw.socialmedia.simulation.tools.ui;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.twitter.Extractor;
import org.jxmapviewer.viewer.GeoPosition;
import twitter4j.GeoLocation;
import twitter4j.GeoQuery;
import twitter4j.Place;
import twitter4j.RateLimitStatus;
import twitter4j.RateLimitStatusEvent;
import twitter4j.RateLimitStatusListener;
import twitter4j.ResponseList;
import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.TwitterFactory;
import twitter4j.TwitterObjectFactory;
import twitter4j.conf.Configuration;
import twitter4j.conf.ConfigurationBuilder;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.InputVerifier;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.SpinnerDateModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class SimpleTweetEditorUI extends JPanel {

    private static final DateTimeFormatter TWITTER_TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH);
    private static final int TWITTER_OLD_MAX_LENGTH = 140;

    // standard default values for attached media
    private static final int DEFAULT_THUMB_HEIGHT = 100;
    private static final int DEFAULT_THUMB_WIDTH = 100;
    private static final int DEFAULT_MEDIA_WIDTH = 226;
    private static final int DEFAULT_MEDIA_HEIGHT = 238;

    private final String[] NAME_PARTS = {
        "salted", "tables", "benign", "sawfly", "sweaty", "noggin",
        "willow", "powder", "untorn", "rewire", "placid", "joists"
    };

    @Parameter(names = {"--skip-date"}, description = "Don't bother creating a 'created_at' field.")
    private boolean skipDate = false;

    @Parameter(names = {"-c", "--credentials"},
        description = "Properties file with Twitter OAuth credentials")
    private String credentialsFile = "./twitter.properties";

    @Parameter(names = {"-h", "-?", "--help"}, description = "Help")
    private static boolean help = false;

    @Parameter(names = {"-v", "--verbose"}, description = "Verbose logging mode")
    private static boolean verbose = false;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Extractor TWITTER_EXTRACTOR = new Extractor();
    private static final int ID_LENGTH = 16;
    private static final Random R = new Random();

    private JComboBox<String> namePicker;
    private JTextArea textArea;
    private JCheckBox useGeoCheckbox;
    private JCheckBox addPlaceCheckbox;
    private GeoPanel geoPanel;
    private JTextArea jsonTextArea;
    private JTextField idTF;
    private JSpinner tsPicker;
    private JTextField mediaUrlTF;
    private JCheckBox useCurrentTS;

    private final SortedComboBoxModel nameCBModel = new SortedComboBoxModel(new String[]{""});

    private final TweetModel model = new TweetModel();

    private final Twitter twitter;

    private volatile boolean placeLookupIsAvailable = true;
    private final Object placesLookupIsAvailableLock = new Object();

    // MAIN

    public static void main(String[] args) throws IOException {
        SimpleTweetEditorUI theApp = new SimpleTweetEditorUI();

        // JCommander instance parses args, populates fields of theApp
        JCommander argsParser = JCommander.newBuilder()
            .addObject(theApp)
            .programName("bin/simple-fake-tweet-generator-ui[.bat]")
            .build();
        try {
            argsParser.parse(args);
        } catch (ParameterException e) {
            System.err.println("Unknown argument parameter:\n  " + e.getMessage());
            help = true;
        }

        if (help) {
            StringBuilder sb = new StringBuilder();
            argsParser.usage(sb);
            System.out.println(sb.toString());
            System.exit(-1);
        }

        loadProxyProperties();

        SwingUtilities.invokeLater(theApp::run);
    }

    SimpleTweetEditorUI() throws IOException {
        model.root = JSON.readValue(freshTweetJson(), JsonNode.class); // initialise the model

        twitter = initTwitter();
    }

    private String freshTweetJson() {
        final String id = generateID();
        final String createdAt = now();
        return "{\"coordinates\":{\"coordinates\":[138.604,-34.918],\"type\":\"Point\"}," +
            "\"created_at\":\""+ createdAt + "\",\"full_text\":\"\",\"id\":" + id +
            ",\"id_str\":\"" + id + "\",\"text\":\"\",\"user\":{\"screen_name\":\"\"}," +
            "\"entities\":{\"media\":[{\"media_url_https\":\"\"}]}}";
    }

    private void run() {
        // Create and set up the window
        JFrame frame = new JFrame("Simple Tweet Editor");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        System.out.println("Frame created");

        buildUI();
        frame.setContentPane(this);
        System.out.println("UI built");

        // Display the window
        frame.setSize(700, 600);
        System.out.println("Size set");
        frame.setVisible(true);

        final String fqName = SimpleTweetEditorUI.class.getName();
        System.out.println(fqName.substring(fqName.lastIndexOf('.') + 1) + " is now running...");
    }

    private void buildUI() {

        // STRUCTURE
        this.setLayout(new BorderLayout());
        this.setBorder(BorderFactory.createEmptyBorder(5,5,5,5));

        // set up left and right panels
        final JPanel left = new JPanel(new GridBagLayout());
        final JPanel right = new JPanel(new BorderLayout());

        left.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 5));

        final JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        splitPane.setOneTouchExpandable(true);
        splitPane.setDividerLocation(400);

        this.add(splitPane, BorderLayout.CENTER);

        // LEFT

        // Row 1: name
        int row = 0;
        final JButton nameButton = new JButton("Screen Name");
        nameButton.setToolTipText("Click to generate a new random name");

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.gridx = 0;
        gbc.insets = new Insets(0, 0, 5, 5);
        left.add(nameButton, gbc);

        namePicker = new JComboBox<>(nameCBModel);
        namePicker.setEditable(true);
        final Icon removeIcon = new ImageIcon(this.getClass().getResource("/icons/Remove-16.png"));
        namePicker.setRenderer(new ButtonComboRenderer(removeIcon, namePicker));
        final Object screenNameObj = model.get("user.screen_name");
        final String sn = screenNameObj != null ? screenNameObj.toString() : "";
        if (sn.equals("\"\"")) { // rescue us from the terrible "" bug!
            model.set("user.screen_name", "");
        } else {
            namePicker.addItem(sn);
            namePicker.setSelectedItem(sn);
        }

        gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 5, 0);
        left.add(namePicker, gbc);

        // Row 2: text field
        row++;
        final JLabel textLabel = new JLabel("Tweet Text");

        gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.gridx = 0;
        gbc.anchor = GridBagConstraints.NORTH;
        gbc.insets = new Insets(0, 0, 5, 5);
        left.add(textLabel, gbc);

        textArea = new JTextArea(4, 30);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        Object text = model.get("full_text");
        if (text == null || text.toString().length() == 0) {
            text = model.get("extended_tweet.full_text");
        }
        if (text == null || text.toString().length() == 0) {
            text = model.get("text");
        }
        if (text!= null && text.toString().equals("\"\"")) {
            // rescue us from the dreaded "" bug (must be Jackson)
            text = "";
        }
        textArea.setText(text != null ? text.toString() : "");

        final JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setMinimumSize(new Dimension(150, 75));

        gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.weighty = 0.0;
        gbc.insets = new Insets(0, 0, 5, 0);
        gbc.fill = GridBagConstraints.BOTH;
        left.add(scrollPane, gbc);


        // Row 3: Image URL
        row++;
        final JButton mediaUrlButton = new JButton("Photo URL");
        mediaUrlButton.setToolTipText("Copy a URL from elsewhere and hit this button to paste it in the photo URL field.");

        gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 5, 5);
        left.add(mediaUrlButton, gbc);

        mediaUrlTF = new JTextField();
        mediaUrlTF.setText(model.get("entities.media.[0].media_url_https").asText(""));

        gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 5, 0);
        left.add(mediaUrlTF, gbc);


        // Row 4: ID
        row++;
        final JButton idButton = new JButton("ID");
        idButton.setToolTipText("Press to re-generate ID");

        gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 5, 5);
        left.add(idButton, gbc);

        idTF = new JTextField();
        idTF.setText(model.get("id_str").asText(""));

        gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 5, 0);
        left.add(idTF, gbc);


        // Row 5: Enable/Disable timestamp
        row++;
        useCurrentTS = new JCheckBox("Use current time");
        useCurrentTS.setToolTipText("Select this to set created_at to 'now' when you generate the JSON.");
        useCurrentTS.setSelected(true);

        gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 5, 0);
        left.add(useCurrentTS, gbc);


        // Row 6: Timestamp
        row++;
        final JButton tsButton = new JButton("Timestamp");
        tsButton.setToolTipText("Press to re-generate timestamp to now");

        gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 5, 5);
        left.add(tsButton, gbc);

        tsPicker = new JSpinner(new SpinnerDateModel());
        JSpinner.DateEditor timeEditor = new JSpinner.DateEditor(tsPicker, "EEE MMM dd HH:mm:ss Z yyyy");
        tsPicker.setEditor(timeEditor);
        tsPicker.setValue(parseCreatedAt());
        tsPicker.setEnabled(! useCurrentTS.isSelected());

        gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 5, 0);
        left.add(tsPicker, gbc);


        // Row 7: use geo checkbox
        row++;
        useGeoCheckbox = new JCheckBox("Use geo?");
        useGeoCheckbox.setSelected(model.get("coordinates") != null);

        gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.gridx = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 0, 5, 0);
        left.add(useGeoCheckbox, gbc);

        addPlaceCheckbox = new JCheckBox("Add \"place\" field?");
        addPlaceCheckbox.setToolTipText(
            "<html>Uses Twitter's APIs to lookup place information for the<br>" +
            "selected geo location (requires Twitter credentials and is<br>" +
            "limited to <font color=red>15 calls per 15 minutes</font>).</html>"
        );
        addPlaceCheckbox.setEnabled(twitter != null);
        addPlaceCheckbox.setVisible(twitter != null); // don't even show it
        addPlaceCheckbox.setSelected(false);

        gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.gridx = 1;
        gbc.anchor = GridBagConstraints.EAST;
        gbc.insets = new Insets(0, 0, 5, 0);
        left.add(addPlaceCheckbox, gbc);


        // Row 8: geo panel
        row++;
        final double[] latLon = lookupLatLon();
        geoPanel = new GeoPanel(latLon[0], latLon[1]);

        gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.weighty = 1.;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(0, 0, 5, 0);
        left.add(geoPanel, gbc);


        // Row 9: generate button
        row++;
        final JButton generateJsonButton = new JButton("Push JSON to global clipboard");

        gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 5, 0);
        left.add(generateJsonButton, gbc);


        // Row 10: new tweet button
        row++;
        final JButton newButton = new JButton("New Tweet");
        newButton.setToolTipText("Refresh the editor for a new Tweet");

        gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        left.add(newButton, gbc);

        // RIGHT

        jsonTextArea = new JTextArea();
        jsonTextArea.setLineWrap(false);
        jsonTextArea.setWrapStyleWord(false);
        jsonTextArea.setEditable(false);
        jsonTextArea.setFont(new Font("Courier New", Font.PLAIN, 12));
        jsonTextArea.setToolTipText(
            "<html>Pretty-printed version of the JSON to be produced.<br>" +
            "(Not editable in this panel.)</html>"
        );

        updateJsonTextArea();

        final JScrollPane jsonScrollPane = new JScrollPane(
            jsonTextArea,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        );
        jsonScrollPane.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));

        right.add(jsonScrollPane, BorderLayout.CENTER);

        final JButton pasteFromClipboardButton = new JButton("Paste Tweet from clipboard");
        pasteFromClipboardButton.setToolTipText(
            "<html>To edit the fields of an existing Tweet,<br>paste its JSON with this button.</html>"
        );

        right.add(pasteFromClipboardButton, BorderLayout.SOUTH);


        // BEHAVIOUR
        useGeoCheckbox.addActionListener(e -> {
            recursivelySetEnabled(geoPanel, useGeoCheckbox.isSelected());
            if (! useGeoCheckbox.isSelected()) {
                model.set("geo", null);
                model.set("coordinates", null);
                addPlaceCheckbox.setEnabled(false);
            } else {
                final double[] ll = geoPanel.getLatLon();
                model.set("geo", makeLatLonJsonNode(ll[0], ll[1]));
                model.set("coordinates", makeLatLonJsonNode(ll[1], ll[0]));
                addPlaceCheckbox.setEnabled(true);
            }
            updateJsonTextArea();
        });
        namePicker.addActionListener(e -> {
            final String newName = (String) namePicker.getSelectedItem();
            namePicker.addItem(newName);
            model.set("user.screen_name", newName);
            updateJsonTextArea();
        });
        nameButton.addActionListener(e -> {
            final String newName = generateName(nameCBModel.getElements());
            namePicker.addItem(newName);
            namePicker.setSelectedItem(newName); // will trigger the ActionListener above
        });
        textArea.getDocument().addDocumentListener(newUpdateOnChangeListener(() -> {
            updateModelAndUIWithNewText(textArea.getText());
        }));
        mediaUrlButton.addActionListener(e -> {
            final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            try {
                // grab the text from the clipboard, safely
                final String copiedUrl = (String) clipboard.getData(DataFlavor.stringFlavor);
                mediaUrlTF.setText(copiedUrl);
            } catch (UnsupportedFlavorException | IOException ex) {
                System.err.println("No URL on the clipboard: " + ex.getMessage());
            }
        });
        mediaUrlTF.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                mediaUrlTF.setSelectionStart(0);
                mediaUrlTF.setSelectionEnd(mediaUrlTF.getText().length());
            }
        });
        mediaUrlTF.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                final String mediaUrl = mediaUrlTF.getText();

                // deal with situation when the first media entity doesn't exist,
                // which happens if you paste in a pre-existing tweet
                if (mediaUrl.trim().isEmpty()) {
                    // remove entity but keep the list
                    model.set("entities.media", JsonNodeFactory.instance.arrayNode());
                    return;
                } else {
                    ensureMediaEntityExists();
                }

                model.set("entities.media.[0].media_url_https", mediaUrl);
                model.set("entities.media.[0].url", mediaUrl);
                model.set("entities.media.[0].display_url", mediaUrl);
                model.set("entities.media.[0].extended_url", mediaUrl);
                model.set("entities.media.[0].type", "photo");
                final String newID = generateID();
                model.set("entities.media.[0].id", BigDecimal.valueOf(Long.parseLong(newID)));
                model.set("entities.media.[0].id_str", newID);
                final String msg = textArea.getText();
                final boolean trailingSpace = ! msg.isEmpty() && msg.charAt(msg.length() - 1) == ' ';
                if (! msg.contains(mediaUrl)) {
                    textArea.setText(msg + (trailingSpace ? "" : " ") + mediaUrl);
                }
                final int indexOfUrl = textArea.getText().indexOf(mediaUrl);
                final int[] indices = new int[]{indexOfUrl, indexOfUrl + mediaUrl.length()};
                model.set("entities.media.[0].indices", indices);
                model.set("entities.media.[0].source_status_id", null);
                model.set("entities.media.[0].source_status_id_str", null);

                setAttachedMediaSize(mediaUrl);

                updateJsonTextArea();
            }
        });
        mediaUrlTF.setInputVerifier(new InputVerifier() {
            @Override
            public boolean verify(JComponent input) {
                final String hopefullyAnUrl = ((JTextField) input).getText();
                try {
                    if (hopefullyAnUrl.trim().length() > 0) {
                        new URL(hopefullyAnUrl);
                    }
                    return true;
                } catch (MalformedURLException e) {
                    System.err.println("Can't parse URL: " + e.getMessage());
                    return false;
                }
            }
        });

        idButton.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(
                SimpleTweetEditorUI.this,
                "Are you sure you want to replace the ID?",
                "Regenerate ID",
                JOptionPane.YES_NO_OPTION) == 0) {
                final String newID = generateID();
                model.set("id_str", newID);
                model.set("id", BigDecimal.valueOf(Long.parseLong(newID)));
                idTF.setText(newID);
                updateJsonTextArea();
            }
        });
        useCurrentTS.addActionListener(e -> tsPicker.setEnabled(! useCurrentTS.isSelected()));
        tsButton.addActionListener(e -> {
            String now = now();
            model.set("created_at", now);
            tsPicker.setValue(parseCreatedAt());
            updateJsonTextArea();
        });
        tsPicker.setInputVerifier(new InputVerifier() {
            @Override
            public boolean verify(JComponent input) {
                final String newTS = ((JTextField) input).getText();
                try {
                    TWITTER_TIMESTAMP_FORMAT.parse(newTS);
                    return true;
                } catch (DateTimeParseException e) {
                    System.err.println("Can't parse timestamp: " + e.getMessage());
                    return false;
                }
            }
        });
        geoPanel.addObserver(e -> {
            if (useGeoCheckbox.isSelected()) {
                GeoPosition centre = (GeoPosition) e.getNewValue();
                model.set("geo", makeLatLonJsonNode(centre.getLatitude(), centre.getLongitude()));
                model.set("coordinates", makeLatLonJsonNode(centre.getLongitude(), centre.getLatitude()));
                SwingUtilities.invokeLater(this::updateJsonTextArea); // makes the UI a little more responsive
            }
        });
        // paste from clipboard to the full json text area
        pasteFromClipboardButton.addActionListener(e -> {
            final String originalContent = jsonTextArea.getText();
            final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            try {
                // grab the text from the clipboard, safely
                final String hopefullyJSON = (String) clipboard.getData(DataFlavor.stringFlavor);
                updateUIFromModel(hopefullyJSON);
            } catch (UnsupportedFlavorException | IOException e1) {
                jsonTextArea.setText(originalContent);
                e1.printStackTrace();
                JOptionPane.showMessageDialog(
                    jsonTextArea,
                    "Failed to paste:\n" + e1.getMessage(),
                    "Paste Error",
                    JOptionPane.WARNING_MESSAGE
                );
            }
        });
        generateJsonButton.addActionListener(e -> {
            final String json = generateJsonFromModel();
            if (json != null) {
                pushToClipboard(json);
                System.out.println(json);

            }
        });
        newButton.addActionListener(e -> {
            try {
                updateUIFromModel(freshTweetJson());
            } catch (IOException e1) {
                e1.printStackTrace();
                JOptionPane.showMessageDialog(
                    jsonTextArea,
                    "Failed to create new tweet:\n" + e1.getMessage(),
                    "New Tweet Error",
                    JOptionPane.WARNING_MESSAGE
                );
            }
        });
    }

    private void ensureMediaEntityExists() {
        if (! model.has("entities.media")) { // add media entity list if it's not there
            final ArrayNode mediaList = JsonNodeFactory.instance.arrayNode();
            mediaList.add(JsonNodeFactory.instance.objectNode());
            model.set("entities.media", mediaList);
        } else if (! model.has("entities.media.[0]")) { // the list is there, but it's empty
            final ArrayNode mediaList = (ArrayNode) model.get("entities.media");
            mediaList.add(JsonNodeFactory.instance.objectNode());
        }
    }

    private void setAttachedMediaSize(final String mediaUrl) {

        // use the defaults until others are available
        System.out.println("Using default media size information for the attached photo");
        model.set(
            "entities.media.[0].sizes",
            buildJsonNodeForMediaSize(
                DEFAULT_THUMB_HEIGHT, DEFAULT_THUMB_WIDTH, DEFAULT_MEDIA_HEIGHT, DEFAULT_MEDIA_WIDTH
            )
        );

        // get the real size info in the background
        new Thread(() -> {
            try {
                final BufferedImage image = ImageIO.read(new URL(mediaUrl));
                final int fullH = image.getHeight();
                final int fullW = image.getWidth();
                final int miniH = DEFAULT_THUMB_HEIGHT;
                final int miniW = (int) Math.floor(miniH / (1.0 * fullH) * fullW);

                SwingUtilities.invokeLater(() -> {
                    ensureMediaEntityExists();
                    model.set("entities.media.[0].sizes", buildJsonNodeForMediaSize(miniH, miniW, fullH, fullW));
                    updateJsonTextArea();
                    System.out.println("media size information updated in background");
                });
            } catch (IOException e) {
                System.err.println("Media URL (" + mediaUrl + ") is not a valid URL: " + e.getMessage());
            }
        }).start();
    }

    private JsonNode buildJsonNodeForMediaSize(
        final int thumbHeight,
        final int thumbWidth,
        final int fullHeight,
        final int fullWidth
    ) {
        try {
            return JSON.readValue("{\n" +
                "  \"thumb\": {\n" +
                "    \"h\": " + thumbHeight + ",\n" +
                "    \"resize\": \"crop\",\n" +
                "    \"w\": " + thumbWidth + "\n" +
                "  },\n" +
                "  \"large\": {\n" +
                "    \"h\": " + fullHeight + ",\n" +
                "    \"resize\": \"fit\",\n" +
                "    \"w\": " + fullWidth + "\n" +
                "  },\n" +
                "  \"medium\": {\n" +
                "    \"h\": " + fullHeight + ",\n" +
                "    \"resize\": \"fit\",\n" +
                "    \"w\": " + fullWidth + "\n" +
                "  },\n" +
                "  \"small\": {\n" +
                "    \"h\": " + fullHeight + ",\n" +
                "    \"resize\": \"fit\",\n" +
                "    \"w\": " + fullWidth + "\n" +
                "  }\n" +
                "}", JsonNode.class);
        } catch (IOException e) {
            e.printStackTrace();
            return JsonNodeFactory.instance.objectNode();
        }
    }

    private Date parseCreatedAt() {
        return Date.from(Instant.from(TWITTER_TIMESTAMP_FORMAT.parse(model.get("created_at").asText(now()))));
    }

    private void updateModelAndUIWithNewText(final String newText) {
        model.set("text", newText);
        model.set("truncated", newText.length() > TWITTER_OLD_MAX_LENGTH);
        model.set("full_text", newText);
        model.set("entities", extractEntitiesAsJsonNodeTree(newText, model.get("entities.media")));
        if (! mediaUrlTF.getText().isEmpty()) {
            final String mediaUrl = mediaUrlTF.getText();
            final int indexOfUrl = textArea.getText().indexOf(mediaUrl);
            final int[] indices = new int[]{indexOfUrl, indexOfUrl + mediaUrl.length()};
            model.set("entities.media.[0].indices", indices);
        }
        updateJsonTextArea();
    }

    private String generateName(final List<String> elements) {
        String newName;
        do {
            final int index1 = (int) Math.floor(Math.random() * NAME_PARTS.length);
            final int index2 = (int) Math.floor(Math.random() * NAME_PARTS.length);
            newName = NAME_PARTS[index1] + "." + NAME_PARTS[index2];
        } while (elements.contains(newName));
        return newName;
    }

    private void updateUIFromModel(final String hopefullyJSON) throws IOException {
        model.root = JSON.readValue(hopefullyJSON, JsonNode.class); // try it out
        if (hopefullyJSON != null) {
            updateJsonTextArea();
            final String sn = model.get("user.screen_name").asText("");
            namePicker.addItem(sn);
            namePicker.setSelectedItem(sn);
            textArea.setText(model.get("full_text").asText("")); // get the full text first
            if (textArea.getText().equals("")) {
                textArea.setText(model.get("extended_tweet.full_text").asText(""));
            }
            if (textArea.getText().equals("")) {
                textArea.setText(model.get("text").asText(""));
            }
            mediaUrlTF.setText(model.get("entities.media.[0].media_url_https").asText(""));
            idTF.setText(model.get("id_str").asText(""));
            tsPicker.setValue(parseCreatedAt());
            final String coords = model.get("coordinates.coordinates") == null
                ? ""
                : model.get("coordinates.coordinates").asText();
            if (coords.equals("null") || coords.length() < 2) { // no value
                final double[] defaultLatLon = getDefaultLatLon();
                geoPanel.setCentre(defaultLatLon[0], defaultLatLon[1]);
                useGeoCheckbox.setSelected(false); // disable if info not present
            } else {
                final String[] parts = coords.split(",");
                geoPanel.setCentre(Double.parseDouble(parts[1]), Double.parseDouble(parts[0]));
                useGeoCheckbox.setSelected(true); // enable if info present
            }
            addPlaceCheckbox.setEnabled(! model.get("place").isNull());
            recursivelySetEnabled(geoPanel, useGeoCheckbox.isSelected());
        }
    }

    private DocumentListener newUpdateOnChangeListener(final Runnable runnable) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { runnable.run(); }

            @Override
            public void removeUpdate(DocumentEvent e) { runnable.run(); }

            @Override
            public void changedUpdate(DocumentEvent e) { runnable.run(); }
        };
    }

    private JsonNode makeLatLonJsonNode(final double first, final double second) {
        try {
            final String jsonContent = "{\"coordinates\":[" + first + "," + second + "],\"type\":\"Point\"}";
            return JSON.readValue(jsonContent, JsonNode.class);
        } catch (IOException e) {
            e.printStackTrace();
            return JsonNodeFactory.instance.nullNode();
        }
    }

    private void updateJsonTextArea() {
        SwingUtilities.invokeLater(() -> {
            try {
                jsonTextArea.setText(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(model.root));
            } catch (JsonProcessingException e) {
                System.err.println("Error generating JSON");
                e.printStackTrace();
            }
        });
    }

    private double[] lookupLatLon() {
        if (model.get("coordinates") == null) {
            return getDefaultLatLon();
        }

        final double lat = model.get("coordinates.coordinates").get(1).asDouble();
        final double lon = model.get("coordinates.coordinates").get(0).asDouble();

        return new double[]{ lat, lon };
    }

    private double[] getDefaultLatLon() {
        // Set the focus (default: Barr Smith Lawns, University of Adelaide, Adelaide, South Australia)
        final double defaultLatitude =
            Double.parseDouble(System.getProperty("initial.latitude", "-34.918"));
        final double defaultLongitude =
            Double.parseDouble(System.getProperty("initial.longitude", "138.604"));

        return new double[]{ defaultLatitude, defaultLongitude };
    }

    private String generateJsonFromModel() {
        try {
            // attempt to include place
            if (placeLookupIsAvailable && twitter != null && addPlaceCheckbox.isSelected() && useGeoCheckbox.isSelected()) {
                final double[] latLon = geoPanel.getLatLon();
                final GeoLocation target = new GeoLocation(latLon[0], latLon[1]);
                GeoQuery query = new GeoQuery(target);
                try {
                    final ResponseList<Place> places = twitter.placesGeo().searchPlaces(query);
                    if (! places.isEmpty()) {
                        final Place p = places.get(0); // no apparent order to places
                        final String jsonForPlace = TwitterObjectFactory.getRawJSON(p);
                        final JsonNode placeNode = JSON.readValue(jsonForPlace, JsonNode.class);
                        model.set("place", placeNode);
                    }
                    maybeDoze(places.getRateLimitStatus()); // would really like to do this elsewhere, not on UI thread
                } catch (TwitterException e) {
                    System.err.println(
                        "Failed asking Twitter for a 'place' corresponding to this location:\n" +
                        e.getErrorMessage() + " [" + e.getErrorCode() + "]"
                    );
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            if (useCurrentTS.isSelected()) {
                model.set("created_at", TWITTER_TIMESTAMP_FORMAT.format(ZonedDateTime.now()));
            }

            return JSON.writeValueAsString(model.root);

        } catch (JsonProcessingException e1) {
            JOptionPane.showMessageDialog(
                SimpleTweetEditorUI.this,
                "Error creating JSON:\n" + e1.getMessage(),
                "Error",
                JOptionPane.WARNING_MESSAGE
            );
            e1.printStackTrace();
        }
        return null;
    }

    private String now() {
        return TWITTER_TIMESTAMP_FORMAT.format(ZonedDateTime.now());
    }

    /**
     * Creates a plausible tweet ID.
     *
     * @return A plausible tweet ID.
     */
    private static String generateID() {
        final StringBuilder idStr = new StringBuilder(Long.toString(System.currentTimeMillis()));
        while (idStr.length() < ID_LENGTH) {
            idStr.append(R.nextInt(10)); // 0-9
        }
        return idStr.toString();
    }


    private void pushToClipboard(final String s) {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new StringSelection(s), null);
    }

    private void recursivelySetEnabled(final JComponent component, final boolean enabled) {
        component.setEnabled(enabled);
        final int numChildren = component.getComponentCount();
        if (numChildren > 0) {
            IntStream.range(0, numChildren).forEach(i -> {
                if (component.getComponent(i) instanceof JComponent) {
                    recursivelySetEnabled((JComponent) component.getComponent(i), enabled);
                }
            });
        }
    }

    private Twitter initTwitter() throws IOException {
        if (Files.notExists(Paths.get(credentialsFile))) {
            return null;
        } else {
            System.out.println("Loading Twitter credentials...");
        }

        final Configuration twitterConfig = makeTwitterConfig(credentialsFile, true);
        final Twitter instance = new TwitterFactory(twitterConfig).getInstance();
        instance.addRateLimitStatusListener(new RateLimitStatusListener() {
            @Override
            public void onRateLimitStatus(RateLimitStatusEvent event) {
                maybeDoze(event.getRateLimitStatus());
            }

            @Override
            public void onRateLimitReached(RateLimitStatusEvent event) {
                maybeDoze(event.getRateLimitStatus());
            }
        });
        return instance;
    }

    /**
     * If the provided {@link RateLimitStatus} indicates that we are about to exceed the rate
     * limit, in terms of number of calls or time window, then sleep for the rest of the period.
     *
     * @param status The current rate limit status of our calls to Twitter
     */
    private void maybeDoze(final RateLimitStatus status) {
        if (status == null) { return; }

        final int secondsUntilReset = status.getSecondsUntilReset();
        final int callsRemaining = status.getRemaining();
        placeLookupIsAvailable = false;
        new Thread(() -> { // off the UI thread
            if (secondsUntilReset < 10 || callsRemaining < 10) {
                final int untilReset = status.getSecondsUntilReset() + 5;
                System.out.printf("Rate limit reached. Waiting %d seconds starting at %s...\n", untilReset, new Date());
                try {
                    Thread.sleep(untilReset * 1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                System.out.println("Resuming...");
            }
            synchronized (placesLookupIsAvailableLock) {
                placeLookupIsAvailable = true;
            }
        }).start();
    }

    /**
     * Builds the {@link Configuration} object with which to connect to Twitter, including
     * credentials and proxy information if it's specified.
     *
     * @return a Twitter4j {@link Configuration} object
     * @throws IOException if there's an error loading the application's {@link #credentialsFile}.
     */
    private static Configuration makeTwitterConfig(
        final String credentialsFile,
        final boolean debug
    ) throws IOException {
        // TODO find a better name than credentials, given it might contain proxy info
        final Properties credentials = loadCredentials(credentialsFile);

        final ConfigurationBuilder conf = new ConfigurationBuilder();
        conf.setTweetModeExtended(true);
        conf.setJSONStoreEnabled(true)
            .setDebugEnabled(debug)
            .setOAuthConsumerKey(credentials.getProperty("oauth.consumerKey"))
            .setOAuthConsumerSecret(credentials.getProperty("oauth.consumerSecret"))
            .setOAuthAccessToken(credentials.getProperty("oauth.accessToken"))
            .setOAuthAccessTokenSecret(credentials.getProperty("oauth.accessTokenSecret"));

        final Properties proxies = loadProxyProperties();
        if (proxies.containsKey("http.proxyHost")) {
            conf.setHttpProxyHost(proxies.getProperty("http.proxyHost"))
                .setHttpProxyPort(Integer.parseInt(proxies.getProperty("http.proxyPort")))
                .setHttpProxyUser(proxies.getProperty("http.proxyUser"))
                .setHttpProxyPassword(proxies.getProperty("http.proxyPassword"));
        }

        return conf.build();
    }

    /**
     * Loads the given {@code credentialsFile} from disk.
     *
     * @param credentialsFile the properties file with the Twitter credentials in it
     * @return A {@link Properties} map with the contents of credentialsFile
     * @throws IOException if there's a problem reading the credentialsFile.
     */
    private static Properties loadCredentials(final String credentialsFile)
        throws IOException {
        final Properties properties = new Properties();
        properties.load(Files.newBufferedReader(Paths.get(credentialsFile)));
        return properties;
    }

    /**
     * Loads proxy information from <code>"./proxy.properties"</code> if it is
     * present. If a proxy host and username are specified by no password, the
     * user is asked to type it in via stdin.
     *
     * @return A {@link Properties} map with proxy credentials.
     */
    private static Properties loadProxyProperties() {
        final Properties properties = new Properties();
        final String proxyFile = "./proxy.properties";
        if (new File(proxyFile).exists()) {
            boolean success = true;
            try (Reader fileReader = Files.newBufferedReader(Paths.get(proxyFile))) {
                properties.load(fileReader);
            } catch (IOException e) {
                System.err.println("Attempted and failed to load " + proxyFile + ": " + e.getMessage());
                success = false;
            }
            if (success && !properties.containsKey("http.proxyPassword")) {
                char[] password = System.console().readPassword("Please type in your proxy password: ");
                properties.setProperty("http.proxyPassword", new String(password));
                properties.setProperty("https.proxyPassword", new String(password));
            }
            properties.forEach((k, v) -> System.setProperty(k.toString(), v.toString()));
        }
        return properties;
    }


    private JsonNode extractEntitiesAsJsonNodeTree(final String newText, final JsonNode mediaEntities) {
        Map<String, List<Object>> entityMap = Maps.newTreeMap();
        entityMap.put("hashtags", Lists.newArrayList());
        entityMap.put("symbols", Lists.newArrayList());
        entityMap.put("user_mentions", Lists.newArrayList());
        entityMap.put("urls", Lists.newArrayList());

        for (Extractor.Entity e : TWITTER_EXTRACTOR.extractURLsWithIndices(newText)) {
            Map<String, Object> urlMap = Maps.newTreeMap();
            urlMap.put("url", e.getValue());
            urlMap.put("extended_url", e.getExpandedURL() != null ? e.getExpandedURL() : e.getValue());
            urlMap.put("display_url", e.getDisplayURL() != null ? e.getDisplayURL() : e.getValue());
            urlMap.put("indices", Arrays.asList(e.getStart(), e.getEnd()));
            entityMap.get("urls").add(urlMap);
        }

        for (Extractor.Entity e : TWITTER_EXTRACTOR.extractMentionedScreennamesWithIndices(newText)) {
            Map<String, Object> mentionMap = Maps.newTreeMap();
            mentionMap.put("screen_name", e.getValue());
            mentionMap.put("name", null); // need reverse-lookup to get most of these values
            mentionMap.put("id", null);
            mentionMap.put("id_str", null);
            mentionMap.put("indices", Arrays.asList(e.getStart(), e.getEnd()));
            entityMap.get("user_mentions").add(mentionMap);
        }

        for (Extractor.Entity e : TWITTER_EXTRACTOR.extractHashtagsWithIndices(newText)) {
            Map<String, Object> hashtagMap = Maps.newTreeMap();
            hashtagMap.put("text", e.getValue());
            hashtagMap.put("indices", Arrays.asList(e.getStart(), e.getEnd()));
            entityMap.get("hashtags").add(hashtagMap);
        }

        for (Extractor.Entity e : TWITTER_EXTRACTOR.extractCashtagsWithIndices(newText)) {
            Map<String, Object> cashtagMap = Maps.newTreeMap();
            cashtagMap.put("text", e.getValue());
            cashtagMap.put("indices", Arrays.asList(e.getStart(), e.getEnd()));
            entityMap.get("symbols").add(cashtagMap);
        }

        final JsonNode entitiesRoot = JSON.valueToTree(entityMap);
        if (mediaEntities != JsonNodeFactory.instance.nullNode()) {
            ((ObjectNode) entitiesRoot).set("media", mediaEntities);
        }
        return entitiesRoot;
    }


    class TweetModel {
        JsonNode root;

        JsonNode get(final String path) {
            return getNested(root, path);
        }

        JsonNode getNested(final JsonNode obj, final String path) {
            if (path.contains(".")) {
                final String head = path.substring(0, path.indexOf('.'));
                final String tail = path.substring(path.indexOf('.') + 1);
                if (head.startsWith("[")) {
                    final int index = Integer.parseInt(head.substring(1, head.length() - 1));
                    if (obj.has(index)) {
                        return getNested(obj.get(index), tail);
                    } else {
                        System.err.println("Could not find index: " + index);
                        return JsonNodeFactory.instance.nullNode(); // error!
                    }
                }
                if (obj.has(head)) {
                    return getNested(obj.get(head), tail);
                } else {
                    System.err.println("Could not find sub-path: " + tail);
                    return JsonNodeFactory.instance.nullNode(); // error!
                }
            } else {
                return obj.has(path) ? obj.get(path) : JsonNodeFactory.instance.nullNode();
            }
        }

        public void set(String path, Object value) {
            setNested(root, path, value);
        }

        void setNested(final JsonNode node, final String path, final Object value) {
            if (path.contains(".")) {
                final String head = path.substring(0, path.indexOf('.'));
                final String tail = path.substring(path.indexOf('.') + 1);
                if (head.startsWith("[")) { // deal with arrays of structures
                    final int index = Integer.parseInt(head.substring(1, head.length() - 1));
                    if (node.has(index)) {
                        setNested(node.get(index), tail, value);
                    } else {
                        System.err.println("Could not find index: " + index);
                        if (verbose) Thread.dumpStack();
                    }
                } else if (node.has(head)) {
                    if (tail.startsWith("[")) {
                        setNested(node.get(head), tail, value);
                    } else {
                        setNested(node.get(head), tail, value);
                    }
                } else {
                    System.err.println("Could not find sub-path: " + tail);
                }
            } else {
                final JsonNodeFactory jsonNodeFactory = JsonNodeFactory.instance;
                ObjectNode obj = null;
                if (path.startsWith("[")) { // deal with arrays of values
                    final int index = Integer.parseInt(path.substring(1, path.length() - 1));
                    if (node.has(index)) {
                        obj = (ObjectNode) node.get(index); // set node to the indexed element
                    } else {
                        System.err.println("Could not find index: " + index);
                        if (verbose) Thread.dumpStack();
                    }
                } else {
                    obj = (ObjectNode) node;
                }

                if (value == null) {
                    obj.set(path, jsonNodeFactory.nullNode());
                } else if (value instanceof JsonNode) {
                    obj.set(path, (JsonNode) value);
                } else if (value instanceof Boolean) {
                    obj.set(path, jsonNodeFactory.booleanNode((Boolean) value));
                } else if (value instanceof BigDecimal) {
                    obj.set(path, jsonNodeFactory.numberNode((BigDecimal) value));
                } else if (value instanceof String) {
                    obj.set(path, jsonNodeFactory.textNode(value.toString()));
                } else if (value instanceof double[]) { //value.getClass().isArray()) {
                    final ArrayNode arrayNode = jsonNodeFactory.arrayNode();
                    double[] array = (double[]) value;
                    for (double d : array) {
                        arrayNode.add(d);
                    }
                    obj.set(path, arrayNode);
                } else if (value instanceof int[]) {
                    final ArrayNode arrayNode = jsonNodeFactory.arrayNode();
                    int[] array = (int[]) value;
                    for (int i : array) {
                        arrayNode.add(i);
                    }
                    obj.set(path, arrayNode);
                }
            }
        }

        public boolean has(final String path) {
            return hasNested(root, path);
        }

        public boolean hasNested(final JsonNode obj, final String path) {
            if (path.contains(".")) {
                final String head = path.substring(0, path.indexOf('.'));
                final String tail = path.substring(path.indexOf('.') + 1);
                if (head.startsWith("[")) {
                    final int index = Integer.parseInt(path.substring(1, path.length() - 1));
                    if (! (obj instanceof ArrayNode)) {
                        return false;
                    }
                    final ArrayNode array = (ArrayNode) obj;
                    if (array.has(index)) {
                        return hasNested(array.get(index), tail);
                    } else {
                        System.err.println("Could not find index: " + index);
                        if (verbose) Thread.dumpStack();
                        return false;
                    }
                } else {
                    return obj.has(head) && hasNested(obj.get(head), tail);
                }
            }
            if (path.startsWith("[")) {
                final int index = Integer.parseInt(path.substring(1, path.length() - 1));
                if (! (obj instanceof ArrayNode)) {
                    return false;
                }
                return obj.has(index);
            } else {
                return obj.has(path);
            }
        }
    }

    /**
     * Borrowed from https://stackoverflow.com/questions/7387299/dynamically-adding-items-to-a-jcombobox
     */
    private class SortedComboBoxModel extends DefaultComboBoxModel<String> {

        private static final long serialVersionUID = 1L;

        public SortedComboBoxModel(final String[] items) {
            Stream.of(items).sorted().filter(Objects::nonNull).forEach(this::addElement);
            setSelectedItem(items[0]);
        }

        @Override
        public void addElement(final String element) {
            if (element == null) return;
            for (int i = 0; i < getSize(); i++) {
                Object elementAtI = getElementAt(i);
                if (elementAtI.equals(element)) {
                    return; // already present
                }
            }

            insertElementAt(element, 0);
        }

        @Override
        public void insertElementAt(final String element, int index) {
            if (element == null) return;
            int size = getSize();
            //  Determine where to insert element to keep model in sorted order
            for (index = 0; index < size; index++) {
                Comparable<String> c = getElementAt(index);
                if (c.compareTo(element) > 0) {
                    break;
                }
            }
            super.insertElementAt(element, index);
        }

        public List<String> getElements() {
            return IntStream.range(0, getSize()).mapToObj(this::getElementAt).collect(Collectors.toList());
        }
    }

    /**
     * Grabbed from https://stackoverflow.com/questions/11065282/display-buttons-in-jcombobox-items
     */
    class ButtonComboRenderer implements ListCellRenderer {
        final Icon icon;
        final JPanel panel;
        final JLabel label;
        final JButton button;

        public ButtonComboRenderer(final Icon removeIcon, final JComboBox<String> combo) {
            icon = removeIcon;
            label = new JLabel();
            button = new JButton(icon);
            button.setPreferredSize(new Dimension(icon.getIconWidth(), icon.getIconHeight()));
            panel = new JPanel(new BorderLayout());
            panel.add(label);
            panel.add(button, BorderLayout.EAST);
            panel.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (button.getX() < e.getX()) {
                        combo.removeItem(label.getText());
                    }
                }
            });
        }
        //so we will install the mouse listener once
        boolean isFirst = true;

        @Override
        public Component getListCellRendererComponent(
            final JList list,
            final Object value,
            final int index,
            final boolean isSelected,
            final boolean cellHasFocus
        ) {
            if (isFirst) {
                isFirst = false;
                list.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent e) {
                        panel.dispatchEvent(e);
                        e.consume();
                    }
                });
            }
            String text = (String) value;
            label.setText(text);
            if (text == null)
                button.setIcon(null);
            else if (button.getIcon() == null)
                button.setIcon(icon);
            panel.setBackground(isSelected ? Color.YELLOW : Color.WHITE);
            panel.setForeground(isSelected ? Color.WHITE : Color.BLACK);
            return panel;
        }
    }
}
