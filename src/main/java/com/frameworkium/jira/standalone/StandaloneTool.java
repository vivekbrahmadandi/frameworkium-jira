package com.frameworkium.jira.standalone;

import com.frameworkium.jira.FileUtils;
import com.frameworkium.jira.exceptions.CsvException;
import com.frameworkium.jira.gherkin.FeatureParser;
import com.frameworkium.jira.properties.Validation;
import com.frameworkium.jira.zapi.Execution;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public class StandaloneTool {

    private String filePath;
    private static final String COMMA = ",";
    private static boolean errorsEncountered = false;

    private final Logger logger = LogManager.getLogger();

    public StandaloneTool(String filePath) {
        this.filePath = filePath;
    }


    //for test purposes only
    public static void main(String[] args) {

        String[] testArgs = {"src/test/resources/csv/noAttachment.csv",
                "",
                "",
                "",
                "MSS Automation",
                "dummy cycle"};

        entryPointForDebugging(testArgs);
    }

    private static void entryPointForDebugging(String[] args){
        StandaloneTool uploader = new StandaloneTool(args[1]);
        uploader.checkArgs(args);
        uploader.checkProperties(args);
        uploader.uploadResultsFromCsv();
    }


    /**
     * Take the Jira & Zephyr parameters from the CLI and set as system properties
     * @param jiraURL
     * @param jiraUsername
     * @param jiraPassword
     * @param resultVersion
     * @param zapiCycleRegEx
     */
    private static void setProperties(String jiraURL, String jiraUsername, String jiraPassword, String resultVersion,
                                      String zapiCycleRegEx){
        System.setProperty("jiraURL",jiraURL);
        System.setProperty("jiraUsername",jiraUsername);
        System.setProperty("jiraPassword",jiraPassword);
        System.setProperty("resultVersion",resultVersion);
        System.setProperty("zapiCycleRegEx",zapiCycleRegEx);

    }

    /**
     * Take the csv file and return a list of ZephyrTestObject for each line which represents a single test
     */
    public void uploadResultsFromCsv(){

        logger.info("Starting Zephyr update");

        collectTests()
                .forEach(test ->
                    new Execution(test.getKey()).update(test.getStatus(), test.getComment(), test.getAttachment()));

        logger.info("Zephyr update complete");

        if (errorsEncountered){
            logger.info("Errors found during update");
        }
    }

    public List<ZephyrTestObject> collectTests(){

        File csvFile = new File(this.filePath);

        try (InputStream inputStream = new FileInputStream(csvFile);
             BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));){

            return br.lines()
                    .map(mapToZephyrTestObject)
                    .collect(Collectors.toList());

        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }

    }

    /**
     * function to take a line of the csv and map it to a ZephyrTestObject
     */
    private Function<String, ZephyrTestObject> mapToZephyrTestObject = (line) -> {
        String[] args = line.split(COMMA,-1);
        int commas = (int) line.chars().filter(ch -> ch == ',').count();
        if (commas < 4){
            //there is no extra comma in the comment section so no need to handle
           return new ZephyrTestObject(args[0],args[1],args[2],args[3]);
        } else {
            //contains a comma in the comment section so we need to handle this
            String id = args[0];
            String passStatus = args[1];
            line = line.substring(id.length() + passStatus.length());
            String[] commentAndAttachment = handleCommaInComment(line);

            return new ZephyrTestObject(id,passStatus,commentAndAttachment[0],commentAndAttachment[1]);
        }
    };

    /**
     * in the comment there may be comma which would mess wit the csv so we need to handle a comment having a comma.
     * @param line in CSV file
     * @return An array of 2 elements, first element is the comment, second element is the attachments
     */
    private String[] handleCommaInComment(String line){
        char doubleQuote = '\"';
        char comma =',';

        int startQuoteIndex = -1;
        int endQuoteIndex = -1;
        int doubleQuoteCount = 0;
        int lastComma = -1;

        char[] charArray = line.toCharArray();

        for (int i = 0; i < charArray.length; i++) {
            if (charArray[i] == doubleQuote) {
                if (startQuoteIndex == -1) {
                    startQuoteIndex = i;
                } else if (endQuoteIndex == -1) {
                    endQuoteIndex = i;
                }
                doubleQuoteCount++;
            } else if (charArray[i] == comma) {
                lastComma = i;
            }

        }

        if (doubleQuoteCount != 2) {
            logger.info("Invalid Comment, should contain 0 or 2 double quotes, skipping csv row");
            throw new CsvException(String.format("Found %s double quotes. Should be 0 or 2 double quotes and only in the " +
                    "comment section of the CSV",doubleQuoteCount));
        }

        String comment = line.substring(startQuoteIndex +1,endQuoteIndex);
        String attachments = line.substring(lastComma +1);

        // check we are not missing last actual comma before attachments in CSV
        if (lastComma < endQuoteIndex){
            logger.info("Missing comma after comment in csv");
            throw new ArrayIndexOutOfBoundsException();
        }

        return new String[]{comment,attachments};

    }


    /**
     * Check that the Jira/Zephyr properties are correct and we can successfully connect to Jira
     * @param args
     */
    void checkProperties(String[] args){
        System.out.print("Checking properties..");
        setProperties(args[2],args[3],args[4],args[5],args[6]);

        if (!Validation.authenticateJira()){
            throw new RuntimeException("Could not authenticate to Jira");
        } else {
            System.out.println("Done");
        }
    }

    /**
     * Check there are the correct amount of arguments parsed into the Jar
     * @param args
     */
    void checkArgs(String[] args){

        String expected = "expected args are:" +
                "\n1) keyword: 'update' or 'sync'" +
                "\n2) csv file path / featureFile or top directory path" +
                "\n3) jiraURL" +
                "\n4) jiraUsername" +
                "\n5) jiraPassword" +
                "\n6) resultVersion" +
                "\n7) zapiCycleRegEx ";

        String errorMessage = "Incorrect amount of args, expected at least 5 but got " + args.length + "\n" + expected;

        System.out.print("Checking args..");
        if (args.length != 7) { throw new RuntimeException(errorMessage); }
        System.out.println("Done");
    }


    /**
     * Looks for all feature files from this top level directory or will just take a single feature file as arg. Then
     * create a new zephyr test for every BDD without a zephyr test. For existing Zephyr Tests update the Zephyr test
     * with any local changes to the BDD
     * @param featureDir either a feature file or a directory to recursively look through for feature files
     */
    public void syncBddsWithZephyr(String featureDir){
        FileUtils.findFeatures(featureDir).forEach(feature -> new FeatureParser(feature).syncTestsWithZephyr());
    }

    public void syncBddsWithZephyr(){
        FileUtils.findFeatures(this.filePath).forEach(feature -> new FeatureParser(feature).syncTestsWithZephyr());
    }

    public static void setErrorsEncountered(Boolean errors){
        errorsEncountered = errors;
    }

    static boolean isErrorsEncountered() {
        return errorsEncountered;
    }

}
