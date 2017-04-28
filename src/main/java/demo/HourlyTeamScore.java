/*
 * Copyright 2017 The Project Authors, see separate AUTHORS file
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package demo;

import java.io.OutputStream;
import java.io.Serializable;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;

import org.apache.avro.reflect.Nullable;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.DefaultCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.Validation;
import org.apache.beam.sdk.transforms.Aggregator;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Sum;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.util.IOChannelFactory;
import org.apache.beam.sdk.util.IOChannelUtils;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.joda.time.LocalDateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/** Compute team scores per hour. */
@SuppressWarnings("deprecation") // TODO(fjp): For IOCHannel Factory
public class HourlyTeamScore {

  static final Duration ONE_HOUR = Duration.standardMinutes(60);

  public interface Options extends PipelineOptions {

    @Description("Path to the data file(s) containing game data.")
    // The default maps to two large Google Cloud Storage files (each ~12GB) holding two subsequent
    // day's worth (roughly) of data.
    @Default.String("gs://apache-beam-samples/game/gaming_data*.csv")
    String getInput();
    void setInput(String value);

    @Description("Output file prefix")
    @Validation.Required
    String getOutputPrefix();
    void setOutputPrefix(String value);
  }

  /** Class to hold info about a game event. */
  @DefaultCoder(SerializableCoder.class)
  static class GameActionInfo implements Serializable {
    /**
     *
     */
    private static final long serialVersionUID = 1L;
    @Nullable String user;
    @Nullable String team;
    @Nullable Integer score;
    @Nullable Long timestamp;

    public GameActionInfo() {}

    public GameActionInfo(String user, String team, Integer score, Long timestamp) {
      this.user = user;
      this.team = team;
      this.score = score;
      this.timestamp = timestamp;
    }

    public String getUser() {
      return this.user;
    }
    public String getTeam() {
      return this.team;
    }
    public Integer getScore() {
      return this.score;
    }
    public Long getTimestamp() {
      return this.timestamp;
    }
  }

  /** DoFn to create file per window. */
  public static class WriteWindowedFilesFn
  extends DoFn<KV<IntervalWindow, Iterable<KV<String, Integer>>>, Void> {

    private static final long serialVersionUID = 1L;
    final byte[] NEWLINE = "\n".getBytes(StandardCharsets.UTF_8);
    final Coder<String> STRING_CODER = StringUtf8Coder.of();

    private static DateTimeFormatter formatter = ISODateTimeFormat.hourMinute();

    String output;

    public WriteWindowedFilesFn(String output) {
      this.output = output;
    }

    public String fileForWindow(String output, ProcessContext context) {
      IntervalWindow window = context.element().getKey();

      String fileName = String.format(
          "%s-%s-%s-%s", output, (new LocalDateTime(window.start())).dayOfWeek().getAsShortText(),
          formatter.print(window.start()), formatter.print(window.end()));

      PaneInfo.Timing timing = context.pane().getTiming();
      if (timing.equals(PaneInfo.Timing.EARLY)) {
        fileName += String.format("_early-%s", formatter.print(System.currentTimeMillis()));
      } else if (timing.equals(PaneInfo.Timing.LATE)) {
        fileName += String.format("_late-%s", formatter.print(System.currentTimeMillis()));
      }

      return fileName;
    }

    @ProcessElement
    public void processElement(ProcessContext context) throws Exception {
      // Build a file name from the window

      String outputShard = fileForWindow(output, context);

      // Open the file and write all the values
      IOChannelFactory factory = IOChannelUtils.getFactory(outputShard);
      OutputStream out = Channels.newOutputStream(factory.create(outputShard, "text/plain"));
      for (KV<String, Integer> wordCount : context.element().getValue()) {
        String line = wordCount.getKey() + ": " + wordCount.getValue();
        //TODOline += ": " + formatter.print(System.currentTimeMillis());
        STRING_CODER.encode(line, out, Coder.Context.OUTER);
        out.write(NEWLINE);
      }
      out.close();
    }
  }

  /** DoFn that keys the element by the window. */
  public static class KeyByWindowFn
  extends DoFn<KV<String, Integer>, KV<IntervalWindow, KV<String, Integer>>> {
    private static final long serialVersionUID = 1L;

    @ProcessElement
    public void processElement(ProcessContext context, IntervalWindow window) {
      context.output(KV.of(window, context.element()));
    }
  }

  /** DoFn to parse raw log lines into structured GameActionInfos. */
  static class ParseEventFn extends DoFn<String, GameActionInfo> {

    private static final long serialVersionUID = 1L;
    // Log and count parse errors.
    private static final Logger LOG = LoggerFactory.getLogger(ParseEventFn.class);
    private final Aggregator<Long, Long> numParseErrors =
        createAggregator("ParseErrors", Sum.ofLongs());
    private static final Counter numParseErrorsCounter = Metrics.counter(ParseEventFn.class, "ParseErrors");

    @ProcessElement
    public void processElement(ProcessContext c) {
      String[] components = c.element().split(",");
      try {
        String user = components[0].trim();
        String team = components[1].trim();
        Integer score = Integer.parseInt(components[2].trim());
        Long timestamp = Long.parseLong(components[3].trim());
        GameActionInfo gInfo = new GameActionInfo(user, team, score, timestamp);
        c.output(gInfo);
      } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
        numParseErrors.addValue(1L);
        numParseErrorsCounter.inc();
        LOG.info("Parse error on " + c.element() + ", " + e.getMessage());
      }
    }
  }

  /** Extract and set the time stamps. */
  static class SetTimestampsFn extends DoFn<GameActionInfo, GameActionInfo> {

    private static final long serialVersionUID = 1L;

    @ProcessElement
    public void processElement(ProcessContext c) {
      c.outputWithTimestamp(c.element(), new Instant(c.element().getTimestamp()));
    }
  }

  /** Key by the team. */
  static class KeyScoreByTeamFn extends DoFn<GameActionInfo, KV<String, Integer>> {

    private static final long serialVersionUID = 1L;

    @ProcessElement
    public void processElement(ProcessContext c) {
      c.output(KV.of(c.element().getTeam(), c.element().getScore()));
    }
  }

  /** Takes a collection of GameActionInfo events and writes the sums per team to files. */
  public static class CalculateTeamScores
  extends PTransform<PCollection<GameActionInfo>, PCollection<Void>> {

    String filepath;

    CalculateTeamScores(String filepath) {
      this.filepath = filepath;
    }

    @Override
    public PCollection<Void> expand(PCollection<GameActionInfo> gameInfo) {

      return gameInfo
          .apply(ParDo.of(new KeyScoreByTeamFn()))

          .apply(Sum.<String>integersPerKey())

          .apply(ParDo.of(new KeyByWindowFn()))
          .apply(GroupByKey.<IntervalWindow, KV<String, Integer>>create())
          .apply(ParDo.of(new WriteWindowedFilesFn(filepath)));
    }
  }

  /** Run a batch pipeline to calculate hourly team scores. */
  public static void main(String[] args) throws Exception {

    Options options =
        PipelineOptionsFactory.fromArgs(args).withValidation().as(Options.class);
    Pipeline pipeline = Pipeline.create(options);

    pipeline
    .apply(TextIO.Read.from(options.getInput()))
    .apply("ParseGameEvent", ParDo.of(new ParseEventFn()))
    .apply("SetTimestamps", ParDo.of(new SetTimestampsFn()))

    .apply("FixedWindows", Window.<GameActionInfo>into(FixedWindows.of(ONE_HOUR)))

    .apply("SumTeamScores", new CalculateTeamScores(options.getOutputPrefix()));

    pipeline.run();
  }
}
