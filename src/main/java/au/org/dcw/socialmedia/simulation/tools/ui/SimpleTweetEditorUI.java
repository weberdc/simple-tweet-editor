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
import org.jxmapviewer.viewer.GeoPosition;

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
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
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
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class SimpleTweetEditorUI extends JPanel {

    class TweetModel {
        JsonNode root;

        JsonNode get(final String path) {
            return getNested(root, path);
        }

        JsonNode getNested(final JsonNode obj, final String path) {
            if (path.contains(".")) {
                final String head = path.substring(0, path.indexOf('.'));
                final String tail = path.substring(path.indexOf('.') + 1);
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
            setNested((ObjectNode) root, path, value);
        }

        void setNested(final ObjectNode obj, final String path, final Object value) {
            if (path.contains(".")) {
                final String head = path.substring(0, path.indexOf('.'));
                final String tail = path.substring(path.indexOf('.') + 1);
                if (obj.has(head)) {
                    setNested((ObjectNode) obj.get(head), tail, value);
                } else {
                    System.err.println("Could not find sub-path: " + tail);
                }
            } else {
                final JsonNodeFactory jsonNodeFactory = JsonNodeFactory.instance;
                if (value == null) {
                    obj.set(path, jsonNodeFactory.nullNode());
                } else if (value instanceof JsonNode) {
                    obj.set(path, (JsonNode) value);
                } else if (value instanceof String) {
                    obj.set(path, jsonNodeFactory.textNode(value.toString()));
                } else if (value instanceof double[]) { //value.getClass().isArray()) {
                    final ArrayNode arrayNode = jsonNodeFactory.arrayNode();
                    double[] array = (double[]) value;
                    arrayNode.add(array[0]);
                    arrayNode.add(array[1]);
                    obj.set(path, arrayNode);
                }
            }
        }

        public boolean has(final String path) {
            return has(root, path);
        }

        public boolean has(final JsonNode obj, final String path) {
            if (path.contains(".")) {
                final String head = path.substring(0, path.indexOf('.'));
                final String tail = path.substring(path.indexOf('.') + 1);
                return obj.has(head) && has(obj.get(head), tail);
            }
            return obj.has(path);
        }
    }

    private static final DateTimeFormatter TWITTER_TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH);

    private final String[] NAME_PARTS = {
        "salted", "tables", "benign", "sawfly", "sweaty", "noggin",
        "willow", "powder", "untorn", "rewire", "placid", "joists"
    };

    @Parameter(names = {"--skip-date"}, description = "Don't bother creating a 'created_at' field.")
    private boolean skipDate = false;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int ID_LENGTH = 16;
    private static final Random R = new Random();
    private static boolean help = false;

    private JComboBox<String> nameCB;
    private JTextArea textArea;
    private JCheckBox useGeoCheckbox;
    private GeoPanel geoPanel;
    private JTextArea jsonTextArea;
    private JTextField idTF;
    private JTextField tsTF;

    private SortedComboBoxModel nameCBModel = new SortedComboBoxModel(new String[]{""});
    private TweetModel model = new TweetModel();

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
    }

    private String freshTweetJson() {
        final String id = generateID();
        final String createdAt = now();
        return "{\"coordinates\":{\"coordinates\":[138.604,-34.918],\"type\":\"Point\"}," +
            "\"created_at\":\""+ createdAt + "\",\"full_text\":\"\",\"id\":" + id +
            ",\"id_str\":\"" + id + "\",\"text\":\"\",\"user\":{\"screen_name\":\"\"}}";
    }

    private void run() {
        // Create and set up the window
        JFrame frame = new JFrame("Tweet Editor");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        System.out.println("Frame created");

        buildUI();
        frame.setContentPane(this);
        System.out.println("UI built");

        // Display the window
//        frame.pack();
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

        nameCB = new JComboBox<>(nameCBModel);
        nameCB.setEditable(true);
        final Icon removeIcon = new ImageIcon(this.getClass().getResource("/icons/Remove-16.png"));
        nameCB.setRenderer(new ButtonComboRenderer(removeIcon, nameCB));
        final Object screenNameObj = model.get("user.screen_name");
        final String sn = screenNameObj != null ? screenNameObj.toString() : "";
        nameCB.addItem(sn);
        nameCB.setSelectedItem(sn);

        gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 5, 0);
        left.add(nameCB, gbc);

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
        Object text = model.get("text");
        if (text == null || text.toString().length() == 0) {
            text = model.get("full_text");
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


        // Row 3: ID
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


        // Row 4: Timestamp
        row++;
        final JButton tsButton = new JButton("Timestamp");
        tsButton.setToolTipText("Press to re-generate timestamp to now");

        gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 5, 5);
        left.add(tsButton, gbc);

        tsTF = new JTextField();
        tsTF.setText(model.get("created_at").asText(now()));

        gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 5, 0);
        left.add(tsTF, gbc);


        // Row 5: use geo checkbox
        row++;
        useGeoCheckbox = new JCheckBox("Use geo?");
        useGeoCheckbox.setSelected(model.get("coordinates") != null);

        gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 0, 5, 0);
        left.add(useGeoCheckbox, gbc);

        // Row 6: geo panel
        row++;
        double[] latLon = lookupLatLon();
        geoPanel = new GeoPanel(latLon[0], latLon[1]);

        gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.weighty = 1.;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(0, 0, 5, 0);
        left.add(geoPanel, gbc);

        // Row 7: generate button
        row++;
        final JButton genButton = new JButton("Push JSON to global clipboard");

        gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        left.add(genButton, gbc);

        // Row 8: new tweet button
        row++;
        final JButton newButton = new JButton("New tweet");

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
            } else {
                final double[] ll = geoPanel.getLatLon();
                model.set("geo", makeLatLonJsonNode(ll[0], ll[1]));
                model.set("coordinates", makeLatLonJsonNode(ll[1], ll[0]));
            }
            updateJsonTextArea();
        });
        nameCB.addActionListener(e -> {
            final String newName = (String) nameCB.getSelectedItem();
            nameCB.addItem(newName);
            model.set("user.screen_name", newName);
            updateJsonTextArea();
        });
        nameButton.addActionListener(e -> {
            final String newName = generateName(nameCBModel.getElements());
            nameCB.addItem(newName);
            nameCB.setSelectedItem(newName); // will trigger the ActionListener above
        });
        textArea.getDocument().addDocumentListener(newUpdateOnChangeListener(() -> {
            model.set("text", textArea.getText());
            updateJsonTextArea();
        }));
        textArea.getDocument().addDocumentListener(newUpdateOnChangeListener(() -> {
            model.set("full_text", textArea.getText());
            updateJsonTextArea();
        }));
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
            }
        });
        tsButton.addActionListener(e -> {
            String now = now();
            model.set("created_at", now);
            tsTF.setText(now);
        });
        tsTF.setInputVerifier(new InputVerifier() {
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
                updateJsonTextArea();
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
        genButton.addActionListener(e -> {
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
            // update the text areas
            updateJsonTextArea();
//            jsonTextArea.setText(hopefullyJSON);
            String sn = model.get("user.screen_name").asText("");
            nameCB.addItem(sn);
            nameCB.setSelectedItem(sn);
            textArea.setText(model.get("text").asText(""));
            if (textArea.getText().equals("")) {
                textArea.setText(model.get("full_text").asText(""));
            }
            idTF.setText(model.get("id_str").asText(""));
            tsTF.setText(model.get("created_at").asText(now()));
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

    private JsonNode makeLatLonJsonNode(double first, double second) {
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
            if (! model.has("created_at")) {
                model.set("created_at", now());
            }
            if (! model.has("id")) {
                final String id = generateID();
                model.set("id", BigDecimal.valueOf(Double.parseDouble(id)));
                model.set("id_str", id);
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
                Comparable c = getElementAt(index);
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
//                        System.out.println("button contains the click remove the item");
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
