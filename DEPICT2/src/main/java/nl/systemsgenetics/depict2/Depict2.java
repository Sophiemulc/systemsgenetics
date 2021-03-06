package nl.systemsgenetics.depict2;

import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.ResourceBundle;
import org.apache.commons.cli.ParseException;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.FileAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.SimpleLayout;
import org.molgenis.genotype.GenotypeDataException;
import org.molgenis.genotype.RandomAccessGenotypeData;
import org.molgenis.genotype.multipart.IncompatibleMultiPartGenotypeDataException;
import org.molgenis.genotype.sampleFilter.SampleFilter;
import org.molgenis.genotype.sampleFilter.SampleIdIncludeFilter;
import org.molgenis.genotype.tabix.TabixFileNotFoundException;
import org.molgenis.genotype.variantFilter.VariantIdIncludeFilter;
import umcg.genetica.math.matrix2.DoubleMatrixDataset;

/**
 *
 * @author Patrick Deelen
 */
public class Depict2 {

	private static final String VERSION = ResourceBundle.getBundle("verion").getString("application.version");
	private static final DateFormat DATE_TIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	private static final SimpleDateFormat LOG_TIME_FORMAT = new SimpleDateFormat("HH:mm:ss");
	private static final Logger LOGGER = Logger.getLogger(Depict2.class);
	private static final String HEADER
			= "  /---------------------------------------\\\n"
			+ "  |                DEPICT2                |\n"
			+ "  |                                       |\n"
			+ "  |  University Medical Center Groningen  |\n"
			+ "  \\---------------------------------------/";

	/**
	 * @param args the command line arguments
	 * @throws java.lang.InterruptedException
	 */
	public static void main(String[] args) throws InterruptedException {

		System.out.println(HEADER);
		System.out.println();
		System.out.println("          --- Version: " + VERSION + " ---");
		System.out.println();
		System.out.println("More information: http://molgenis.org/systemsgenetics");
		System.out.println();

		Date currentDataTime = new Date();
		String startDateTime = DATE_TIME_FORMAT.format(currentDataTime);
		System.out.println("Current date and time: " + startDateTime);
		System.out.println();

		System.out.flush(); //flush to make sure header is before errors
		Thread.sleep(25); //Allows flush to complete

		Depict2Options options;

		if (args.length == 0) {
			Depict2Options.printHelp();
			return;
		}

		try {
			options = new Depict2Options(args);
		} catch (ParseException ex) {
			System.err.println("Error parsing commandline: " + ex.getMessage());
			Depict2Options.printHelp();
			return;
		}

		if (options.getLogFile().getParentFile() != null && !options.getLogFile().getParentFile().isDirectory()) {
			if (!options.getLogFile().getParentFile().mkdirs()) {
				System.err.println("Failed to create output folder: " + options.getLogFile().getParent());
				System.exit(1);
			}
		}

		try {
			FileAppender logFileAppender = new FileAppender(new SimpleLayout(), options.getLogFile().getCanonicalPath(), false);
			ConsoleAppender logConsoleInfoAppender = new ConsoleAppender(new InfoOnlyLogLayout());
			LOGGER.getRootLogger().removeAllAppenders();
			LOGGER.getRootLogger().addAppender(logFileAppender);

			LOGGER.info("DEPICT2 version: " + VERSION);
			LOGGER.info("Current date and time: " + startDateTime);

			LOGGER.getRootLogger().addAppender(logConsoleInfoAppender);

			if (options.isDebugMode()) {
				LOGGER.setLevel(Level.DEBUG);
			} else {
				LOGGER.setLevel(Level.INFO);
			}
		} catch (IOException e) {
			System.err.println("Failed to create logger: " + e.getMessage());
			System.exit(1);
		}

		options.printOptions();

		try {
			switch (options.getMode()) {
				case CONVERT_EQTL:
					convertEqtlToBin(options);
					break;
				case CONVERT_TXT:
					convertTxtToBin(options);
					break;
				case RUN:
					run(options);
					break;
			}
		} catch (TabixFileNotFoundException e) {
			System.err.println("Problem running mode: " + options.getMode());
			System.err.println("Tabix file not found for input data at: " + e.getPath() + "\n"
					+ "Please see README on how to create a tabix file");
			LOGGER.fatal("Tabix file not found for input data at: " + e.getPath(), e);
			System.exit(1);
		} catch (IOException e) {
			System.err.println("Problem running mode: " + options.getMode());
			System.err.println("Error accessing input data: " + e.getMessage());
			LOGGER.fatal("Error accessing input data: " + e.getMessage(), e);
			System.exit(1);
		} catch (IncompatibleMultiPartGenotypeDataException e) {
			System.err.println("Problem running mode: " + options.getMode());
			System.err.println("Error combining the impute genotype data files: " + e.getMessage());
			LOGGER.fatal("Error combining the impute genotype data files: " + e.getMessage(), e);
		} catch (GenotypeDataException e) {
			System.err.println("Problem running mode: " + options.getMode());
			System.err.println("Error reading input data: " + e.getMessage());
			LOGGER.fatal("Error reading input data: " + e.getMessage(), e);
			System.exit(1);
		} catch (Exception e) {
			System.err.println("Problem running mode: " + options.getMode());
			System.err.println("Error meesage: " + e.getMessage());
			System.err.println("See log file for stack trace");
			LOGGER.fatal("Error: " + e.getMessage(), e);
			System.exit(1);
		}
		LOGGER.info("Completed mode: " + options.getMode());

		currentDataTime = new Date();
		LOGGER.info("Current date and time: " + DATE_TIME_FORMAT.format(currentDataTime));

	}

	public static void run(Depict2Options options) throws IOException, Exception {

		final List<String> variantsInZscoreMatrix = readMatrixAnnotations(new File(options.getGwasZscoreMatrixPath() + ".rows.txt"));
		final List<String> phenotypesInZscoreMatrix = readMatrixAnnotations(new File(options.getGwasZscoreMatrixPath() + ".cols.txt"));

		LOGGER.info("Number of phenotypes in GWAS matrix: " + phenotypesInZscoreMatrix.size());
		LOGGER.info("Number of variants in GWAS matrix: " + variantsInZscoreMatrix.size());

		RandomAccessGenotypeData referenceGenotypeData = loadGenotypes(options, variantsInZscoreMatrix);

//		Iterator<GeneticVariant> it = referenceGenotypeData.iterator();
//		for(int i = 0 ; i < 5 ; i++){
//			if(!it.hasNext()){
//				System.out.println("No more variants");
//			}
//			GeneticVariant v = it.next();
//			System.out.println(v.getSequenceName() + ":" + v.getStartPos() + " " + v.getVariantId().getPrimairyId());
//		}
//		System.out.println("---");
//		GeneticVariant v = referenceGenotypeData.getVariantsByPos("1", 10177).iterator().next();
//		System.out.println(v.getSequenceName() + ":" + v.getStartPos() + " " + v.getVariantId().getPrimairyId());
//		System.out.println("---");
//		for (GeneticVariant v2 : referenceGenotypeData.getVariantsByRange("1", 10100, 10200)) {
//			System.out.println(v2.getSequenceName() + ":" + v2.getStartPos() + " " + v2.getVariantId().getPrimairyId());
//		}
//		System.out.println("---");
//		for (GeneticVariant v2 : referenceGenotypeData.getVariantsByRange("1", 11919466, 12132045)) {
//			System.out.println(v2.getSequenceName() + ":" + v2.getStartPos() + " " + v2.getVariantId().getPrimairyId());
//		}
//		
		LOGGER.info("Done loading genotype data");

		List<Gene> genes = readGenes(options.getGeneInfoFile());

		LOGGER.info("Loaded " + genes.size() + " genes");

		DoubleMatrixDataset<String, String> genePvalues = CalculateGenePvalues.calculatorGenePvalues(options.getGwasZscoreMatrixPath(), new GenotypeCorrelationGenotypes(referenceGenotypeData), genes, options.getWindowExtend(), options.getMaxRBetweenVariants(), options.getNumberOfPermutations(), options.getOutputBasePath());

		LOGGER.info("Finished calculating gene p-values");

		genePvalues.save(options.getOutputBasePath() + "_genePvalues.txt");
	}

	private static RandomAccessGenotypeData loadGenotypes(Depict2Options options, List<String> variantsToInclude) throws IOException {
		final RandomAccessGenotypeData referenceGenotypeData;

		final SampleFilter sampleFilter;
		if (options.getGenotypeSamplesFile() != null) {
			sampleFilter = readSampleFile(options.getGenotypeSamplesFile());
		} else {
			sampleFilter = null;
		}

		referenceGenotypeData = options.getGenotypeType().createFilteredGenotypeData(options.getGenotypeBasePath(), 10000, new VariantIdIncludeFilter(new HashSet<>(variantsToInclude)), sampleFilter, null, 0.34f);

		return referenceGenotypeData;
	}

	protected static final List<String> readMatrixAnnotations(File file) throws IOException {

		final CSVParser parser = new CSVParserBuilder().withSeparator('\t').withIgnoreQuotations(true).build();
		final CSVReader reader = new CSVReaderBuilder(new BufferedReader(new FileReader(file))).withCSVParser(parser).build();

		ArrayList<String> identifiers = new ArrayList<>();

		String[] nextLine;
		while ((nextLine = reader.readNext()) != null) {

			identifiers.add(nextLine[0]);

		}

		return identifiers;

	}

	private static List<Gene> readGenes(File geneFile) throws IOException {

		final CSVParser parser = new CSVParserBuilder().withSeparator('\t').withIgnoreQuotations(true).build();
		final CSVReader reader = new CSVReaderBuilder(new BufferedReader(new FileReader(geneFile))).withCSVParser(parser).withSkipLines(1).build();

		final ArrayList<Gene> genes = new ArrayList<>();

		String[] nextLine;
		while ((nextLine = reader.readNext()) != null) {

			genes.add(new Gene(nextLine[0], nextLine[1], Integer.parseInt(nextLine[2]), Integer.parseInt(nextLine[3])));

		}

		return genes;

	}

	private static SampleIdIncludeFilter readSampleFile(File sampleFile) throws IOException {

		final CSVParser parser = new CSVParserBuilder().withSeparator('\t').withIgnoreQuotations(true).build();
		final CSVReader reader = new CSVReaderBuilder(new BufferedReader(new FileReader(sampleFile))).withCSVParser(parser).withSkipLines(0).build();

		final HashSet<String> samples = new HashSet<>();

		String[] nextLine;
		while ((nextLine = reader.readNext()) != null) {

			samples.add(nextLine[0]);

		}

		return new SampleIdIncludeFilter(samples);

	}

	private static void convertTxtToBin(Depict2Options options) throws IOException {

		DoubleMatrixDataset<String, String> matrix = DoubleMatrixDataset.loadDoubleTextData(options.getGwasZscoreMatrixPath(), '\t');
		matrix.saveBinary(options.getOutputBasePath());

	}

	private static void convertEqtlToBin(Depict2Options options) throws IOException {

		DoubleMatrixDataset<String, String> matrix = DoubleMatrixDataset.loadTransEqtlExpressionMatrix(options.getGwasZscoreMatrixPath());
		matrix.saveBinary(options.getOutputBasePath());

	}
	
	public static String formatMsForLog(long ms){
		return LOG_TIME_FORMAT.format(new Date(ms));	
	}

}
