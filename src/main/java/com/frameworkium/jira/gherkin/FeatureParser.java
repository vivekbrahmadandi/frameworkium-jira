package com.frameworkium.jira.gherkin;

import com.frameworkium.jira.JiraConfig;
import com.frameworkium.jira.api.Issue;
import com.frameworkium.jira.api.NewIssue;
import gherkin.AstBuilder;
import gherkin.Parser;
import gherkin.ast.GherkinDocument;
import gherkin.pickles.Pickle;
import gherkin.pickles.Compiler;
import gherkin.pickles.PickleStep;
import gherkin.pickles.PickleTag;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Class to parse a feature and sync all data with Zephyr, the feature is the source of truth
 * - Check test is in zephyr by looking for tag
 * - Add new test to zephyr if needed
 * - Update existing zephyr test to latest version
 */
public class FeatureParser {
    private static final Logger logger = LogManager.getLogger();


    private static final String ZEPHYR_TAG_PREFIX = "@TestCaseId:";
    private static final String SCENARIO_KEYWORD = "Scenario:";
    private static final String NEW_LINE = "\n";
    private static final String INDENTATION = "  ";

    private String featurePath;

    public List<Pickle> getPickles() {
        return pickles;
    }

    private List<Pickle> pickles;

    public FeatureParser(String featurePath) {
        this.featurePath = featurePath;
        pickles = parse();
    }

    public static void main(String[] args) {
//        FeatureParser featureParser = new FeatureParser();
//        List<Pickle> pickles = new FeatureParser().parse(
//                "src/test/resources/gherkinParser/features/test.feature");
//
//        pickles.forEach(pickle -> {
//            System.out.println(pickle.getName());
//            pickle.getSteps().forEach(pickleStep -> System.out.println(pickleStep.getText()));
//            pickle.getTags().forEach(pickleTag -> System.out.println(pickleTag.getName()));
//
//            String s = String.valueOf(featureParser.getZephyrId(pickle));
//            System.out.println(s);
//        });


    }


    private List<Pickle> parse(){
        String feature = com.frameworkium.jira.FileUtils.readFile(this.featurePath);
        Parser<GherkinDocument> parser = new Parser<>(new AstBuilder());
        GherkinDocument gherkinDocument = parser.parse(feature);
        List<Pickle> pickles = new Compiler().compile(gherkinDocument);
        return pickles;
    }

    //todo update zephyr for tests that pickleHasZephyrTag == true
    public void syncWithZephyr(){
        this.pickles.stream()
                .filter(pickle1 -> !pickleHasZephyrTag(pickle1))
                .forEach(pickle -> {
                    String zId = addTestToZephyr(pickle);
                    addTagsToScenario(pickle, zId);
                });

    }

    /**
     * Check each tag for a zephyr tag checking it contains @TestCaseId:<zephyr tag> then query zephyr to check tag exists
     * @param pickle
     * @return
     */
    public boolean pickleHasZephyrTag(Pickle pickle){
        return pickle.getTags().stream()
                .map(PickleTag::getName)
                .filter(tag -> tag.contains(ZEPHYR_TAG_PREFIX))
                .map(this::stripZephyrTag)
                .anyMatch(tag -> new Issue(tag).found());
    }

    /**
     * remove @TestCaseId: if it is present from the tag
     * @param zephyrTag
     * @return
     */
    private String stripZephyrTag(String zephyrTag){
        return zephyrTag.replace(ZEPHYR_TAG_PREFIX, "");
    }

    /**
     * Find any zephyr test id tags on the scenario
     * @param pickle
     * @return the stripped zephyr id if present or Optional.empty if no zephyr id was found
     */
    //todo unit tests
    public Optional<String> getZephyrId(Pickle pickle){
        Optional<String> zephyrId = pickle.getTags()
                .stream()
                .map(PickleTag::getName)
                .filter(pickleTag -> pickleTag.startsWith(ZEPHYR_TAG_PREFIX))
                .map(this::stripZephyrTag)
                .findFirst();

        return zephyrId;
    }

    /**
     * Create a new test in zephyr
     * @param pickle
     * @return Issue Id of new zephyr test
     */
    public String addTestToZephyr(Pickle pickle){
        String endpoint = JiraConfig.JIRA_REST_PATH + "issue";

        //TODO get the bdd out to display properly
        String scenarioTitle = pickle.getName();
        String scenarioSteps = pickle.getSteps().stream()
                                    .map(PickleStep::getText)
                                    .map(step -> step + "\n")
                                    .collect(Collectors.joining(","))
                                    .replace(",","");

//        System.out.println(scenarioSteps);

        NewIssue newTest = new NewIssue("TP",
                                    scenarioTitle,
                                "Test Generated By frameworkium-jira (automation)",
                                        "Test",
                                        scenarioSteps
        );

        String zephyrId = JiraConfig.getJIRARequestSpec()
                .given()
                    .contentType("application/json")
                    .body(newTest.generateJson())
                .expect()
                    .statusCode(201)
                .when()
                    .post(endpoint)
                .thenReturn()
                    .jsonPath().getString("key");

        logger.info("Zephyr Test Created: " + zephyrId);

        return zephyrId;

    }

    public void updateZephyrTest(){
        //todo put request to push/update existing source bdd into zephyr
        //update title
        //update bdd section
        //possibly just reuse some of addTestToZephyr(Pickle pickle)
    }

    /**
     * Adds a tag to a Scenario in a feature file
     * 1 - Reads feature file as a stream
     * 2 - for each line will look to match with the name of the scenario
     * 3 - replace that line with a line with the tag followed by original scenario line
     * 4 - transform stream of strings to bytes
     * 5 - write bytes to original file (overwrite)
     * @param pickle aka scenario you want to update
     * @param zephyrId ID of Zephyr test
     */
    public void addTagsToScenario(Pickle pickle, String zephyrId){
        String tag = ZEPHYR_TAG_PREFIX + zephyrId;
        String scenarioNameToUpdate = pickle.getName();

        //regex to match: (any number of white space)Scenario:(0 or 1 whitespace)(name of scenario)
        String scenarioTitle = String.format("( *)%s( ?)%s",SCENARIO_KEYWORD, scenarioNameToUpdate);
        String scenarioLine = INDENTATION + SCENARIO_KEYWORD + " " + scenarioNameToUpdate;
        File file = new File(this.featurePath);
        String fileContext = null;

        try {
            fileContext = FileUtils.readFileToString(file);
            fileContext = fileContext.replaceAll(scenarioTitle,
                     INDENTATION + tag + NEW_LINE + scenarioLine);
            FileUtils.write(file, fileContext);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }


}