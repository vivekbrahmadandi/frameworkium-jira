package com.frameworkium.jira.gherkin;

import com.frameworkium.base.properties.Property;
import com.frameworkium.jira.FileUtils;
import com.frameworkium.jira.JiraConfig;
import com.frameworkium.jira.api.Issue;
import com.frameworkium.jira.api.NewIssue;
import com.frameworkium.jira.api.NewIssueBuilder;
import gherkin.AstBuilder;
import gherkin.Parser;
import gherkin.ast.GherkinDocument;
import gherkin.pickles.Pickle;
import gherkin.pickles.Compiler;
import gherkin.pickles.PickleStep;
import lombok.Getter;
//import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Class to parse a feature and sync all data with Zephyr, the feature is the source of truth
 * - Check test is in zephyr by looking for tag
 * - Add new test to zephyr if needed
 * - Update existing zephyr test to latest version
 */
public class FeatureParser {
    private static final Logger logger = LogManager.getLogger();


    private static final String SCENARIO_KEYWORD = "Scenario:";
    private static final String NEW_LINE = "\n";
    private static final String INDENTATION = "  ";
    private static final String Z_TEST_GENERATED_MESSAGE = "Test Generated By frameworkium-jira (automation)";

    private String featurePath;

    @Getter
    private List<Pickle> pickles;

    public FeatureParser(String featurePath) {
        this.featurePath = featurePath;
        pickles = parse();
    }

    /**
     * Parse feature file and transform into a list of pickles
     * @return list of pickles, each pickle being a scenario
     */
    private List<Pickle> parse(){
        String feature = FileUtils.readFile(this.featurePath);
        Parser<GherkinDocument> parser = new Parser<>(new AstBuilder());
        GherkinDocument gherkinDocument = parser.parse(feature);
        return new Compiler().compile(gherkinDocument);
    }

//    /**
//     * Filter all Scenario that do not have an existing Zephyr test tag or do not have the @NoZephyr tag, then create
//     * a test case in Zpehyr for each Scenario. For the ID of the new Zephyr test case add a corresponding cucumber tag
//     * to the original cucumber Scenario
//     */
//    public void syncTestsWithZephyr(){
//        this.pickles.stream()
//                .filter(pickle -> !GherkinUtils.pickleHasZephyrTag(pickle.getTags()))
//                .filter(pickle -> !GherkinUtils.pickleContainsTag(pickle.getTags(),JiraConfig.NO_UPLOAD_TO_ZEPHYR))
//                .forEach(pickle -> {
//                    String zId = addTestToZephyr(pickle);
//                    addTagsToScenario(pickle, zId);
//                });
//    }


    /**
     * Splits the Feature into Scenarios with Zephyr tags and Scenarios without. Scenarios with a zephyr file will have
     * the Zephyr Test case updated in case there have been any changes or edits. Scenarios without a zephyr tag will
     * have a new Zepyhr test case created AND the the ID of the new Zephyr test case will be added to the corresponding
     * Scenario as a cucumber test. Scenarios with the @NoZephyr tag will not have a Zephyr test case created.
     */
    public void syncTestsWithZephyr(){
        Map<Boolean,List<Pickle>> groups = this.pickles
                .stream()
                .collect(Collectors.partitioningBy(pickle -> GherkinUtils.pickleHasZephyrTag(pickle.getTags())));

        List<Pickle> hasZephyrTag = groups.get(true);
        List<Pickle> noZephyrTag = groups.get(false);

        noZephyrTag.stream()
                .filter(pickle -> !GherkinUtils.pickleContainsTag(pickle.getTags(),JiraConfig.NO_UPLOAD_TO_ZEPHYR))
                .forEach(pickle -> {
                    String zId = addTestToZephyr(pickle);
                    addTagsToScenario(pickle, zId);
                });

        hasZephyrTag
                .forEach(pickle -> {
                    String zId = GherkinUtils.getZephyrIdFromTags(pickle.getTags()).get();
                    new Issue(zId).updateZephyrTest(pickle.getName(), GherkinUtils.generateBddFromSteps(pickle.getSteps()));
                });
    }


    /**
     * Create a new Zephyr Test Case
     * @param pickle the pickle form of a Scenario use to create a Zephyr Test Case
     * @return the Zephyr ID of the newly created Test Case
     */
    private String addTestToZephyr(Pickle pickle) {
        String scenarioTitle = pickle.getName();
        String scenarioSteps = GherkinUtils.generateBddFromSteps(pickle.getSteps());
        String project = Property.JIRA_PROJECT_KEY.getValue();

        return new NewIssueBuilder()
                .setKey(project)
                .setSummary(scenarioTitle)
                .setDescription(Z_TEST_GENERATED_MESSAGE)
                .setIssueType(NewIssue.IssueType.TEST)
                .setBddField(scenarioSteps)
                .createNewIssue()
                .create();
    }


    /**
     * Overloaded Method of addTagsToScenario(String scenarioName, String zephyrId) that takes a pickle instead of string
     * for scenario name
     * @param pickle aka scenario you want to update
     * @param zephyrId Zephyr ID that we need to add to scenario as
     */
    public void addTagsToScenario(Pickle pickle, String zephyrId){
        String scenarioNameToUpdate = pickle.getName();

        addTagsToScenario(scenarioNameToUpdate, zephyrId);
    }

    /**
     * Adds a tag to a Scenario in a feature file
     * 1 - Reads feature file as a stream
     * 2 - for each line will look to match with the issueType of the scenario
     * 3 - replace that line with a line with the tag followed by original scenario line
     * 4 - transform stream of strings to bytes
     * 5 - write bytes to original file (overwrite)
     * @param scenarioName name of scenario to look for
     * @param zephyrId ID of Zephyr test
     */
    public void addTagsToScenario(String scenarioName, String zephyrId){
        String tag = JiraConfig.ZEPHYR_TAG_PREFIX + zephyrId;

        //regex to match: (any number of white space)Scenario:(0 or 1 whitespace)(issueType of scenario)
        String scenarioTitle = String.format("( *)%s( ?)%s",SCENARIO_KEYWORD, scenarioName);
        String scenarioLine = INDENTATION + SCENARIO_KEYWORD + " " + scenarioName;
        File file = new File(this.featurePath);
        String fileContext = null;

        try {
            fileContext = com.frameworkium.jira.FileUtils.readFile(this.featurePath);
            fileContext = fileContext.replaceAll(scenarioTitle,
                    INDENTATION + tag + NEW_LINE + scenarioLine);
            org.apache.commons.io.FileUtils.write(file, fileContext);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
