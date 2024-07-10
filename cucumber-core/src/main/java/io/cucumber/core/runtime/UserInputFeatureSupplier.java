package io.cucumber.core.runtime;

import io.cucumber.core.feature.FeatureIdentifier;
import io.cucumber.core.feature.FeatureParser;
import io.cucumber.core.feature.Options;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.logging.Logger;
import io.cucumber.core.logging.LoggerFactory;
import io.cucumber.core.resource.ResourceScanner;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.management.OperatingSystemMXBean;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

import static io.cucumber.core.feature.FeatureIdentifier.isFeature;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.joining;

import io.cucumber.core.gherkin.Feature;

import javax.swing.*;
import java.util.List;

//public final class UserInputFeatureSupplier implements FeatureSupplier {
//    @Override
//    public List<Feature> get() {
//        return List.of();
//    }
//}

public final class UserInputFeatureSupplier implements FeatureSupplier, ActionListener, WindowListener {

    private static final Logger log = LoggerFactory.getLogger(UserInputFeatureSupplier.class);

    private final ResourceScanner<Feature> featureScanner;

    private String currentText = "";

    private final JTextArea textArea;

    private boolean shouldReleaseGet = false;

    private volatile boolean stop = false;

    public UserInputFeatureSupplier(Supplier<ClassLoader> classLoader, Options featureOptions, FeatureParser parser) {
        // Create a JFrame?
        JFrame frame = new JFrame("Gherkin Input");
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        frame.addWindowListener(this);

        JPanel panel = new JPanel();
        JButton goButton = new JButton("Go");
        goButton.addActionListener(this);
        this.textArea = new JTextArea();
        this.textArea.setRows(50);
        this.textArea.setColumns(100);
        JScrollPane scrollPane = new JScrollPane(this.textArea);

        panel.add(this.textArea);
        panel.add(goButton);
        frame.add(panel);

        frame.pack();
        frame.setVisible(true);

        this.featureScanner = new ResourceScanner<>(
                classLoader,
                FeatureIdentifier::isFeature,
                parser::parseResource);
    }

    @Override
    public List<Feature> get() {
        while(!this.shouldStop()) {
            while(!this.shouldReleaseGet && !this.shouldStop()) {
                Thread.onSpinWait();
            }
            this.shouldReleaseGet = false;
            if (!this.shouldStop()) {
                return this.go();
            }
        }
        return List.of();
    }

    @Override
    public boolean isContinuous() {
        return true;
    }

    @Override
    public boolean shouldStop() {
        return this.stop;
    }

    private List<Feature> go() {
        // Grab the text and run it as a feature
        String finalText = this.currentText;

        boolean needsFeatureTag = false;
        boolean needsScenarioTag = false;

        // If the text does not have a feature, add a temp one
        if (!finalText.contains("Feature:")) {
            // Add a temp feature tag to the beginning
            needsFeatureTag = true;
        }

        // If the text does not have a scenario, add a temp one
        if (!finalText.contains("Scenario:")) {
            needsScenarioTag = true;
        }

        // Get the current timestamp as a string
        DateTimeFormatter timeStampPattern = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss");
        String currentTimeAsString =  timeStampPattern.format(LocalDateTime.now());

        String prependText = "";
        if (needsFeatureTag) {
            prependText += "Feature: Feature_" + currentTimeAsString + System.lineSeparator();
        }

        if (needsScenarioTag) {
            prependText += "Scenario: Scenario_" + currentTimeAsString + System.lineSeparator();
        }

        // Add the prepend text to final text
        finalText = prependText + finalText;

        // First, write the text to a temp file
        String filePath =
                System.getProperty("java.io.tmpdir") +
                currentTimeAsString +
                ".feature";
        File tempFile = new File(filePath);
        try (FileWriter fw = new FileWriter(tempFile)) {
            // Write the file
            fw.write(finalText);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        final FeatureBuilder builder = new FeatureBuilder();
        List<Feature> found = featureScanner.scanForResourcesUri(tempFile.toURI());
        if (!found.isEmpty()) {
            found.forEach(builder::addUnique);
        }

        return builder.build();
    }

    private List<Feature> loadFeatures(List<URI> featurePaths) {
        log.debug(() -> "Loading features from " + featurePaths.stream().map(URI::toString).collect(joining(", ")));
        final FeatureBuilder builder = new FeatureBuilder();

        for (URI featurePath : featurePaths) {
            List<Feature> found = featureScanner.scanForResourcesUri(featurePath);
            if (found.isEmpty() && isFeature(featurePath)) {
                throw new IllegalArgumentException("Feature not found: " + featurePath);
            }
            found.forEach(builder::addUnique);
        }

        return builder.build();
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        String s = e.getActionCommand();
        if (s.equals("Go")) {
            // set the text and release the get()
            this.currentText = this.textArea.getText();
            this.shouldReleaseGet = true;
        }
    }

    @Override
    public void windowOpened(WindowEvent e) {}

    @Override
    public void windowClosing(WindowEvent e) {
        this.stop = true;
        this.shouldReleaseGet = true;
    }

    @Override
    public void windowClosed(WindowEvent e) {}

    @Override
    public void windowIconified(WindowEvent e) {}

    @Override
    public void windowDeiconified(WindowEvent e) {}

    @Override
    public void windowActivated(WindowEvent e) {}

    @Override
    public void windowDeactivated(WindowEvent e) {}

    static final class FeatureBuilder {

        private final Map<String, Map<String, Feature>> sourceToFeature = new HashMap<>();
        private final List<Feature> features = new ArrayList<>();

        List<Feature> build() {
            List<Feature> features = new ArrayList<>(this.features);
            features.sort(comparing(Feature::getUri));
            return features;
        }

        void addUnique(Feature parsedFeature) {
            String parsedFileName = getFileName(parsedFeature);

            Map<String, Feature> existingFeatures = sourceToFeature.get(parsedFeature.getSource());
            if (existingFeatures != null) {
                // Same contents but different file names was probably
                // intentional
                Feature existingFeature = existingFeatures.get(parsedFileName);
                if (existingFeature != null) {
                    log.error(() -> "" +
                            "Duplicate feature found: " +
                            parsedFeature.getUri() + " was identical to " + existingFeature.getUri() + "\n" +
                            "\n" +
                            "This typically happens when you configure cucumber to look " +
                            "for features in the root of your project.\nYour build tool " +
                            "creates a copy of these features in a 'target' or 'build'" +
                            "directory.\n" +
                            "\n" +
                            "If your features are on the class path consider using a class path URI.\n" +
                            "For example: 'classpath:com/example/app.feature'\n" +
                            "Otherwise you'll have to provide a more specific location");
                    return;
                }
            }
            sourceToFeature.putIfAbsent(parsedFeature.getSource(), new HashMap<>());
            sourceToFeature.get(parsedFeature.getSource()).put(parsedFileName, parsedFeature);
            features.add(parsedFeature);
        }

        private String getFileName(Feature feature) {
            String uri = feature.getUri().getSchemeSpecificPart();
            int i = uri.lastIndexOf("/");
            return i > 0 ? uri.substring(i) : uri;
        }

    }

}
