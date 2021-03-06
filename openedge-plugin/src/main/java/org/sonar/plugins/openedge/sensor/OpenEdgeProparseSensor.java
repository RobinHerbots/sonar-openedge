/*
 * OpenEdge plugin for SonarQube
 * Copyright (c) 2015-2020 Riverside Software
 * contact AT riverside DASH software DOT fr
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.plugins.openedge.sensor;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;

import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.TokenSource;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.prorefactor.core.ABLNodeType;
import org.prorefactor.core.JsonNodeLister;
import org.prorefactor.core.ProToken;
import org.prorefactor.core.ProparseRuntimeException;
import org.prorefactor.proparse.antlr4.IncludeFileNotFoundException;
import org.prorefactor.proparse.antlr4.Proparse;
import org.prorefactor.proparse.antlr4.XCodedFileException;
import org.prorefactor.refactor.RefactorSession;
import org.prorefactor.treeparser.ParseUnit;
import org.prorefactor.treeparser.TreeParserSymbolScope;
import org.sonar.api.SonarProduct;
import org.sonar.api.batch.fs.FilePredicates;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.InputFile.Type;
import org.sonar.api.batch.fs.TextPointer;
import org.sonar.api.batch.measure.Metric;
import org.sonar.api.batch.rule.ActiveRule;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.batch.sensor.error.NewAnalysisError;
import org.sonar.api.batch.sensor.issue.NewIssue;
import org.sonar.api.batch.sensor.issue.NewIssueLocation;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.plugins.openedge.api.Constants;
import org.sonar.plugins.openedge.api.checks.OpenEdgeProparseCheck;
import org.sonar.plugins.openedge.foundation.CPDCallback;
import org.sonar.plugins.openedge.foundation.InputFileUtils;
import org.sonar.plugins.openedge.foundation.OpenEdgeComponents;
import org.sonar.plugins.openedge.foundation.OpenEdgeMetrics;
import org.sonar.plugins.openedge.foundation.OpenEdgeProjectHelper;
import org.sonar.plugins.openedge.foundation.OpenEdgeRulesDefinition;
import org.sonar.plugins.openedge.foundation.OpenEdgeSettings;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLReader;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import com.progress.xref.CrossReference;

import eu.rssw.listing.CodeBlock;
import eu.rssw.listing.ListingParser;

public class OpenEdgeProparseSensor implements Sensor {
  private static final Logger LOG = Loggers.get(OpenEdgeProparseSensor.class);

  // IoC
  private final OpenEdgeSettings settings;
  private final OpenEdgeComponents components;

  // Internal use
  private final DocumentBuilderFactory dbFactory;
  private final DocumentBuilder dBuilder;
  private final JAXBContext context;
  private final Unmarshaller unmarshaller;
  private final SAXParserFactory saxParserFactory;

  // File statistics
  private int numFiles;
  private int numXREF;
  private int numListings;
  private int numFailures;
  private int ncLocs;

  // Timing statistics
  private Map<String, Long> ruleTime = new HashMap<>();
  private long parseTime = 0L;
  private long xmlParseTime = 0L;
  private long maxParseTime = 0L;
  private Map<Integer, Long> decisionTime = new HashMap<>();
  private Map<Integer, Long> maxK = new HashMap<>();

  // Proparse debug
  List<String> debugFiles = new ArrayList<>();

  public OpenEdgeProparseSensor(OpenEdgeSettings settings, OpenEdgeComponents components) {
    this.settings = settings;
    this.components = components;
    dbFactory = DocumentBuilderFactory.newInstance();
    saxParserFactory = SAXParserFactory.newInstance();
    saxParserFactory.setNamespaceAware(false);

    try {
      dbFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      saxParserFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      dBuilder = dbFactory.newDocumentBuilder();
      context = JAXBContext.newInstance("com.progress.xref", CrossReference.class.getClassLoader());
      unmarshaller = context.createUnmarshaller();
    } catch (ParserConfigurationException | JAXBException | SAXNotRecognizedException | SAXNotSupportedException caught) {
      throw new IllegalStateException(caught);
    }
  }

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor.onlyOnLanguage(Constants.LANGUAGE_KEY).name(getClass().getSimpleName()).onlyWhenConfiguration(
        config -> !config.getBoolean(Constants.SKIP_PROPARSE_PROPERTY).orElse(false));
  }

  @Override
  public void execute(SensorContext context) {
    if (settings.skipProparseSensor())
      return;
    settings.init();
    components.initializeLicense(context);
    components.initializeChecks(context);
    for (Map.Entry<ActiveRule, OpenEdgeProparseCheck> entry : components.getProparseRules().entrySet()) {
      ruleTime.put(entry.getKey().ruleKey().toString(), 0L);
    }
    RefactorSession session = settings.getProparseSession();

    FilePredicates predicates = context.fileSystem().predicates();
    for (InputFile file : context.fileSystem().inputFiles(
        predicates.and(predicates.hasLanguage(Constants.LANGUAGE_KEY), predicates.hasType(Type.MAIN)))) {
      LOG.debug("Parsing {}", file);
      numFiles++;

      if (settings.isIncludeFile(file.filename())) {
        parseIncludeFile(context, file, session);
      } else {
        parseMainFile(context, file, session);
      }
      if (context.isCancelled()) {
        LOG.info("Analysis cancelled...");
        return;
      }
    }

    executeAnalytics(context);
    logStatistics();
    generateProparseDebugIndex();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void parseIncludeFile(SensorContext context, InputFile file, RefactorSession session) {
    long startTime = System.currentTimeMillis();
    ParseUnit lexUnit = null;
    try {
      lexUnit = new ParseUnit(InputFileUtils.getInputStream(file),
          InputFileUtils.getRelativePath(file, context.fileSystem()), session);
      lexUnit.lexAndGenerateMetrics();
    } catch (UncheckedIOException caught) {
      numFailures++;
      if (caught.getCause() instanceof XCodedFileException) {
        LOG.error("Unable to generate file metrics for xcode'd file '{}", file);
      } else {
        LOG.error("Unable to generate file metrics for file '" + file + "'", caught);
      }
      return;
    } catch (ProparseRuntimeException caught) {
      LOG.error("Unable to generate file metrics for file '" + file + "'", caught);
      return;
    }
    updateParseTime(System.currentTimeMillis() - startTime);

    if (lexUnit.getMetrics() != null) {
      // Saving LOC and COMMENTS metrics
      context.newMeasure().on(file).forMetric((Metric) CoreMetrics.NCLOC).withValue(
          lexUnit.getMetrics().getLoc()).save();
      ncLocs += lexUnit.getMetrics().getLoc();
      context.newMeasure().on(file).forMetric((Metric) CoreMetrics.COMMENT_LINES).withValue(
          lexUnit.getMetrics().getComments()).save();
    }

    if (!settings.useSimpleCPD()) {
      try {
        lexUnit = new ParseUnit(InputFileUtils.getInputStream(file),
            InputFileUtils.getRelativePath(file, context.fileSystem()), session);
        TokenSource stream = lexUnit.lex();
        OpenEdgeCPDSensor.processTokenSource(file, context.newCpdTokens().onFile(file), stream);
      } catch (UncheckedIOException | ProparseRuntimeException caught) {
        // Nothing here
      }
    }
  }

  private Document parseXREF(File xrefFile) {
    Document doc = null;
    if ((xrefFile != null) && xrefFile.exists()) {
      LOG.debug("Parsing XML XREF file {}", xrefFile.getAbsolutePath());
      try (InputStream inpStream = new FileInputStream(xrefFile)) {
        long startTime = System.currentTimeMillis();
        doc = dBuilder.parse(
            settings.useXrefFilter() ? new InvalidXMLFilterStream(settings.getXrefBytes(), inpStream) : inpStream);
        xmlParseTime += (System.currentTimeMillis() - startTime);
      } catch (SAXException | IOException caught) {
        LOG.error("Unable to parse XREF file " + xrefFile.getAbsolutePath(), caught);
      }
    }

    return doc;
  }

  private CrossReference jaxbXREF(File xrefFile) {
    CrossReference doc = null;
    if ((xrefFile != null) && xrefFile.exists()) {
      LOG.debug("Parsing XML XREF file {}", xrefFile.getAbsolutePath());
      try (InputStream inpStream = new FileInputStream(xrefFile)) {
        long startTime = System.currentTimeMillis();
        InputSource is = new InputSource(
            settings.useXrefFilter() ? new InvalidXMLFilterStream(settings.getXrefBytes(), inpStream) : inpStream);
        XMLReader reader = saxParserFactory.newSAXParser().getXMLReader();
        SAXSource source = new SAXSource(reader, is);
        doc = (CrossReference) unmarshaller.unmarshal(source);
        xmlParseTime += (System.currentTimeMillis() - startTime);
        numXREF++;
      } catch (JAXBException | SAXException | ParserConfigurationException | IOException caught) {
        LOG.error("Unable to parse XREF file " + xrefFile.getAbsolutePath(), caught);
      }
    }

    return doc;
  }

  private void parseMainFile(SensorContext context, InputFile file, RefactorSession session) {
    CrossReference xref = null;
    Document doc = null;
    if (context.runtime().getProduct() == SonarProduct.SONARQUBE) {
      xref = jaxbXREF(settings.getXrefFile(file));
      if (settings.parseXrefDocument())
        doc = parseXREF(settings.getXrefFile(file));
    } else if (context.runtime().getProduct() == SonarProduct.SONARLINT) {
      xref = jaxbXREF(settings.getSonarlintXrefFile(file));
      if (settings.parseXrefDocument())
        doc = parseXREF(settings.getSonarlintXrefFile(file));
      settings.parseHierarchy(file);
    }

    File listingFile = settings.getListingFile(file);
    List<Integer> trxBlocks = new ArrayList<>();
    if ((listingFile != null) && listingFile.exists() && (listingFile.getAbsolutePath().indexOf(' ') == -1)) {
      try {
        ListingParser parser = new ListingParser(listingFile, InputFileUtils.getRelativePath(file, context.fileSystem()));
        for (CodeBlock block : parser.getTransactionBlocks()) {
          trxBlocks.add(block.getLineNumber());
        }
        numListings++;
      } catch (IOException caught) {
        LOG.error("Unable to parse listing file for " + file, caught);
      }
    } else {
      LOG.debug("Listing file for '{}' not found or contains space character - Was looking for '{}'", file,
          listingFile);
    }
    context.newMeasure().on(file).forMetric((Metric) OpenEdgeMetrics.TRANSACTIONS).withValue(
        Joiner.on(",").join(trxBlocks)).save();
    context.newMeasure().on(file).forMetric((Metric) OpenEdgeMetrics.NUM_TRANSACTIONS).withValue(
        trxBlocks.size()).save();

    ParseUnit unit = null;
    long startTime = System.currentTimeMillis();

    try {
      unit = new ParseUnit(InputFileUtils.getInputStream(file), InputFileUtils.getRelativePath(file, context.fileSystem()), session);
      unit.treeParser01();
      unit.attachXref(doc);
      unit.attachXref(xref);
      unit.attachTransactionBlocks(trxBlocks);
      unit.attachTypeInfo(session.getTypeInfo(unit.getRootScope().getClassName()));
      updateParseTime(System.currentTimeMillis() - startTime);
    } catch (UncheckedIOException caught) {
      numFailures++;
      if ((caught.getCause() != null) && (caught.getCause() instanceof XCodedFileException)) {
        XCodedFileException cause = (XCodedFileException) caught.getCause();
        LOG.error("Unable to parse {} - Can't read xcode'd file {}", file, cause.getFileName());
      } else if ((caught.getCause() != null) && (caught.getCause() instanceof IncludeFileNotFoundException)) {
        IncludeFileNotFoundException cause = (IncludeFileNotFoundException) caught.getCause();
        LOG.error("Unable to parse {} - Can't find include file '{}' from '{}'", file, cause.getIncludeName(), cause.getFileName());
      } else {
        LOG.error("Unable to parse " + file + " - IOException was caught - Please report this issue", caught);
      }
      return;
    } catch (ParseCancellationException caught) {
      RecognitionException cause = (RecognitionException) caught.getCause();
      ProToken tok = (ProToken) cause.getOffendingToken();
      if (settings.displayStackTraceOnError()) {
        LOG.error("Error during code parsing for " + file + " at position " + tok.getFileName() + ":" + tok.getLine()
            + ":" + tok.getCharPositionInLine(), cause);
      } else {
        LOG.error("Error during code parsing for {} at position {}:{}:{}", file, tok.getFileName(), tok.getLine(),
            tok.getCharPositionInLine());
      }
      numFailures++;

      TextPointer strt = null;
      TextPointer end = null;
      if (InputFileUtils.getRelativePath(file, context.fileSystem()).equals(tok.getFileName())) {
        try {
          strt = file.newPointer(tok.getLine(), tok.getCharPositionInLine() - 1);
          end = file.newPointer(tok.getLine(), tok.getCharPositionInLine());
        } catch (IllegalArgumentException uncaught) { // NO-SONAR
          // Nothing
        }
      }

      if (context.runtime().getProduct() == SonarProduct.SONARLINT) {
        NewAnalysisError analysisError = context.newAnalysisError();
        analysisError.onFile(file);
        analysisError.message(Strings.nullToEmpty(cause.getMessage()) + " in " + tok.getFileName() + ":" + tok.getLine()
            + ":" + tok.getCharPositionInLine());
        if (strt != null)
          analysisError.at(strt);
        analysisError.save();
      } else {
        NewIssue issue = context.newIssue().forRule(
            RuleKey.of(Constants.STD_REPOSITORY_KEY, OpenEdgeRulesDefinition.PROPARSE_ERROR_RULEKEY));
        NewIssueLocation loc = issue.newLocation().on(file).message(Strings.nullToEmpty(cause.getMessage()) + " in "
            + tok.getFileName() + ":" + tok.getLine() + ":" + tok.getCharPositionInLine());
        if ((strt != null) && (end != null))
          loc.at(file.newRange(strt, end));
        issue.at(loc);
        issue.save();
      }

      return;
    } catch (RuntimeException caught) {
      LOG.error("Error during code parsing for " + InputFileUtils.getRelativePath(file, context.fileSystem()), caught);
      numFailures++;
      NewIssue issue = context.newIssue();
      issue.forRule(RuleKey.of(Constants.STD_REPOSITORY_KEY, OpenEdgeRulesDefinition.PROPARSE_ERROR_RULEKEY)).at(
          issue.newLocation().on(file).message(Strings.nullToEmpty(caught.getMessage()))).save();
      return;
    }

    if (context.runtime().getProduct() == SonarProduct.SONARQUBE) {
      if (!settings.useSimpleCPD()) {
        computeCpd(context, file, unit);
      }
      computeSimpleMetrics(context, file, unit);
      computeCommonMetrics(context, file, unit);
      computeComplexity(context, file, unit);
    }

    if (settings.useProparseDebug()) {
      generateProparseDebugFile(file, unit);
    }

    try {
      for (Map.Entry<ActiveRule, OpenEdgeProparseCheck> entry : components.getProparseRules().entrySet()) {
        LOG.debug("ActiveRule - Internal key {} - Repository {} - Rule {}", entry.getKey().internalKey(),
            entry.getKey().ruleKey().repository(), entry.getKey().ruleKey().rule());
        startTime = System.currentTimeMillis();
        entry.getValue().sensorExecute(file, unit);
        ruleTime.put(entry.getKey().ruleKey().toString(),
            ruleTime.get(entry.getKey().ruleKey().toString()) + System.currentTimeMillis() - startTime);
      }
    } catch (RuntimeException caught) {
      LOG.error("Error during rule execution for " + file, caught);
    }
  }

  private void updateParseTime(long elapsedTime) {
    LOG.debug("{} milliseconds to generate ParseUnit", elapsedTime);
    parseTime += elapsedTime;
    if (maxParseTime < elapsedTime) {
      maxParseTime = elapsedTime;
    }
  }

  private void executeAnalytics(SensorContext context) {
    if (!settings.useAnalytics())
      return;

    StringBuilder data = new StringBuilder(String.format( // NOSONAR Influx requires LF
        "proparse,product=%1$s,sid=%2$s files=%3$d,failures=%4$d,parseTime=%5$d,maxParseTime=%6$d,version=\"%7$s\",ncloc=%8$d\n",
        context.runtime().getProduct().toString().toLowerCase(), OpenEdgeProjectHelper.getServerId(context), numFiles,
        numFailures, parseTime, maxParseTime, context.runtime().getApiVersion().toString(), ncLocs));
    for (Entry<String, Long> entry : ruleTime.entrySet()) {
      data.append(String.format("rule,product=%1$s,sid=%2$s,rulename=%3$s ruleTime=%4$d\n", // NOSONAR
          context.runtime().getProduct().toString().toLowerCase(), OpenEdgeProjectHelper.getServerId(context),
          entry.getKey(), entry.getValue()));
    }

    try {
      final URL url = new URL("http://sonar-analytics.rssw.eu/write?db=sonar");
      HttpURLConnection connx = (HttpURLConnection) url.openConnection();
      connx.setRequestMethod("POST");
      connx.setConnectTimeout(2000);
      connx.setDoOutput(true);
      DataOutputStream wr = new DataOutputStream(connx.getOutputStream());
      wr.writeBytes(data.toString());
      wr.flush();
      wr.close();
      connx.getResponseCode();
    } catch (IOException uncaught) {
      LOG.debug("Unable to send analytics: {}", uncaught.getMessage());
    }
  }

  private void logStatistics() {
    LOG.info("{} files proparse'd, {} XML files, {} listing files, {} failure(s), {} NCLOCs", numFiles, numXREF,
        numListings, numFailures, ncLocs);
    LOG.info("AST Generation | time={} ms", parseTime);
    LOG.info("XML Parsing    | time={} ms", xmlParseTime);
    // Sort entries by rule name
    ruleTime.entrySet().stream().sorted(
        (Entry<String, Long> obj1, Entry<String, Long> obj2) -> obj1.getKey().compareTo(obj2.getKey())).forEach(
            (Entry<String, Long> entry) -> LOG.info("Rule {} | time={} ms", entry.getKey(), entry.getValue()));
    if (!decisionTime.isEmpty()) {
      LOG.info("ANTRL4 - 25 longest rules");
      decisionTime.entrySet().stream().sorted((o1, o2) -> o2.getValue().compareTo(o1.getValue())).limit(25).forEach(
          entry -> LOG.info("Rule {} - {} | time={} ms", entry.getKey(),
              Proparse.ruleNames[Proparse._ATN.getDecisionState(entry.getKey().intValue()).ruleIndex],
              entry.getValue()));
    }
    if (!maxK.isEmpty()) {
      LOG.info("ANTRL4 - 25 Max lookeahead rules");
      maxK.entrySet().stream().sorted((o1, o2) -> o2.getValue().compareTo(o1.getValue())).limit(25).forEach(
          entry -> LOG.info("Rule {} - {} | Max lookahead: {}", entry.getKey(),
              Proparse.ruleNames[Proparse._ATN.getDecisionState(entry.getKey().intValue()).ruleIndex],
              entry.getValue()));
    }
  }

  private void generateProparseDebugFile(InputFile file, ParseUnit unit) {
    String fileName = ".proparse/" + file.relativePath() + ".json";
    File dbgFile = new File(fileName);
    dbgFile.getParentFile().mkdirs();
    try (PrintWriter writer = new PrintWriter(dbgFile)) {
      JsonNodeLister nodeLister = new JsonNodeLister(unit.getTopNode(), writer, ABLNodeType.LEFTPAREN,
          ABLNodeType.RIGHTPAREN, ABLNodeType.COMMA, ABLNodeType.PERIOD, ABLNodeType.LEXCOLON, ABLNodeType.OBJCOLON,
          ABLNodeType.THEN, ABLNodeType.END);
      nodeLister.print();
      debugFiles.add(file.relativePath() + ".json");
    } catch (IOException caught) {
      LOG.error("Unable to write proparse debug file", caught);
    }
  }

  private void generateProparseDebugIndex() {
    if (settings.useProparseDebug()) {
      try (InputStream from = this.getClass().getResourceAsStream("/debug-index.html");
          OutputStream to = new FileOutputStream(new File(".proparse/index.html"))) {
        ByteStreams.copy(from, to);
      } catch (IOException caught) {
        LOG.error("Error while writing index.html", caught);
      }
      try (PrintWriter writer = new PrintWriter(new File(".proparse/index.json"))) {
        boolean first = true;
        writer.println("var data= { \"files\": [");
        for (String str : debugFiles) {
          if (!first) {
            writer.write(',');
          } else {
            first = false;
          }
          writer.println("{ \"file\": \"" + str + "\" }");
        }
        writer.println("]}");
      } catch (IOException uncaught) {
        LOG.error("Error while writing debug index", uncaught);
      }
    }
  }

  private void computeCpd(SensorContext context, InputFile file, ParseUnit unit) {
    CPDCallback cpdCallback = new CPDCallback(context, file, settings, unit);
    unit.getTopNode().walk(cpdCallback);
    cpdCallback.getResult().save();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void computeSimpleMetrics(SensorContext context, InputFile file, ParseUnit unit) {
    // Saving LOC and COMMENTS metrics
    context.newMeasure().on(file).forMetric((Metric) CoreMetrics.NCLOC).withValue(unit.getMetrics().getLoc()).save();
    ncLocs += unit.getMetrics().getLoc();
    context.newMeasure().on(file).forMetric((Metric) CoreMetrics.COMMENT_LINES).withValue(
        unit.getMetrics().getComments()).save();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void computeCommonMetrics(SensorContext context, InputFile file, ParseUnit unit) {
    context.newMeasure().on(file).forMetric((Metric) CoreMetrics.STATEMENTS).withValue(
        unit.getTopNode().queryStateHead().size()).save();
    int numProcs = 0;
    int numFuncs = 0;
    int numMethds = 0;
    for (TreeParserSymbolScope child : unit.getRootScope().getChildScopesDeep()) {
      int scopeType = child.getRootBlock().getNode().getType();
      switch (scopeType) {
        case Proparse.PROCEDURE:
          boolean externalProc = false;
          /* FIXME for (JPNode node : child.getRootBlock().getNode().getDirectChildren()) {
            if ((node.getType() == ProParserTokenTypes.IN_KW) || (node.getType() == ProParserTokenTypes.SUPER)
                || (node.getType() == ProParserTokenTypes.EXTERNAL)) {
              externalProc = true;
            }
          } */
          if (!externalProc) {
            numProcs++;
          }
          break;
        case Proparse.FUNCTION:
          boolean externalFunc = false;
          /* FIXME for (JPNode node : child.getRootBlock().getNode().getDirectChildren()) {
            if ((node.getType() == ProParserTokenTypes.IN_KW) || (node.getType() == ProParserTokenTypes.FORWARDS)) {
              externalFunc = true;
            }
          } */
          if (!externalFunc) {
            numFuncs++;
          }
          break;
        case Proparse.METHOD:
          numMethds++;
          break;
        default:

      }
    }
    context.newMeasure().on(file).forMetric((Metric) OpenEdgeMetrics.INTERNAL_PROCEDURES).withValue(numProcs).save();
    context.newMeasure().on(file).forMetric((Metric) OpenEdgeMetrics.INTERNAL_FUNCTIONS).withValue(numFuncs).save();
    context.newMeasure().on(file).forMetric((Metric) OpenEdgeMetrics.METHODS).withValue(numMethds).save();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void computeComplexity(SensorContext context, InputFile file, ParseUnit unit) {
    // Interfaces don't contribute to complexity
    if (unit.getRootScope().isInterface())
      return;
    int complexity = 0;
    int complexityWithInc = 0;
    // Procedure has a main block, so starting at 1
    if (!unit.getRootScope().isClass()) {
      complexity++;
      complexityWithInc++;
    }

    complexity += unit.getTopNode().queryMainFile(ABLNodeType.IF, ABLNodeType.REPEAT, ABLNodeType.FOR, ABLNodeType.WHEN,
        ABLNodeType.AND, ABLNodeType.OR, ABLNodeType.RETURN, ABLNodeType.PROCEDURE, ABLNodeType.FUNCTION, ABLNodeType.METHOD,
        ABLNodeType.ENUM).size();
    complexityWithInc += unit.getTopNode().query(ABLNodeType.IF, ABLNodeType.REPEAT, ABLNodeType.FOR, ABLNodeType.WHEN,
        ABLNodeType.AND, ABLNodeType.OR, ABLNodeType.RETURN, ABLNodeType.PROCEDURE, ABLNodeType.FUNCTION, ABLNodeType.METHOD,
        ABLNodeType.ENUM).size();
    context.newMeasure().on(file).forMetric((Metric) CoreMetrics.COMPLEXITY).withValue(complexity).save();
    context.newMeasure().on(file).forMetric((Metric) OpenEdgeMetrics.COMPLEXITY).withValue(complexityWithInc).save();
  }

}
