/*
 * SonarQube JavaScript Plugin
 * Copyright (C) 2011-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
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
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarsource.nodejs;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.sonar.api.config.Configuration;
import org.sonar.api.internal.google.common.annotations.VisibleForTesting;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

class NodeCommandBuilderImpl implements NodeCommandBuilder {

  private static final Logger LOG = Loggers.get(NodeCommandBuilderImpl.class);

  private static final String NODE_EXECUTABLE_DEFAULT = "node";

  private static final String[] NODE_EXECUTABLE_PROPERTIES = {"sonar.nodejs.executable", "sonar.typescript.node", "sonar.css.node"};

  private static final Pattern NODEJS_VERSION_PATTERN = Pattern.compile("v?(\\d+)\\.\\d+\\.\\d+");

  private final NodeCommand.ProcessWrapper processWrapper;
  private Integer minNodeVersion;
  private Configuration configuration;
  private List<String> args = new ArrayList<>();
  private List<String> nodeJsArgs = new ArrayList<>();
  private Consumer<String> outputConsumer = LOG::info;
  private Consumer<String> errorConsumer = LOG::error;
  private String scriptFilename;

  NodeCommandBuilderImpl(NodeCommand.ProcessWrapper processWrapper) {
    this.processWrapper = processWrapper;
  }

  @Override
  public NodeCommandBuilder minNodeVersion(int minNodeVersion) {
    this.minNodeVersion = minNodeVersion;
    return this;
  }

  @Override
  public NodeCommandBuilder configuration(Configuration configuration) {
    this.configuration = configuration;
    return this;
  }

  @Override
  public NodeCommandBuilder maxOldSpaceSize(int maxOldSpaceSize) {
    nodeJsArgs("--max-old-space-size=" + maxOldSpaceSize);
    return this;
  }

  @Override
  public NodeCommandBuilder nodeJsArgs(String... nodeJsArgs) {
    this.nodeJsArgs.addAll(Arrays.asList(nodeJsArgs));
    return this;
  }

  @Override
  public NodeCommandBuilder script(String scriptFilename) {
    this.scriptFilename = scriptFilename;
    return this;
  }

  @Override
  public NodeCommandBuilder scriptArgs(String... args) {
    this.args.addAll(Arrays.asList(args));
    return this;
  }

  @Override
  public NodeCommandBuilder outputConsumer(Consumer<String> consumer) {
    this.outputConsumer = consumer;
    return this;
  }

  @Override
  public NodeCommandBuilder errorConsumer(Consumer<String> consumer) {
    this.errorConsumer = consumer;
    return this;
  }

  /**
   * First will check Node.js version by running {@code node -v}, then
   * returns {@link NodeCommand} instance.
   *
   * @throws NodeCommandException when actual Node.js version doesn't satisfy minimum version requested.
   */
  @Override
  public NodeCommand build() {
    String nodeExecutable = retrieveNodeExecutableFromConfig(configuration);
    boolean isCompatible = isCompatibleNodeVersion(nodeExecutable);
    if (!isCompatible) {
      throw new NodeCommandException("Node.js is not compatible.");
    }
    if (nodeJsArgs.isEmpty() && scriptFilename == null && args.isEmpty()) {
      throw new IllegalArgumentException("Missing arguments for Node.js.");
    }
    if (scriptFilename == null && !args.isEmpty()) {
      throw new IllegalArgumentException("No script provided, but script arguments found.");
    }
    return new NodeCommand(
      processWrapper,
      nodeExecutable,
      nodeJsArgs,
      scriptFilename,
      args,
      outputConsumer,
      errorConsumer);
  }

  private boolean isCompatibleNodeVersion(String nodeExecutable) {
    if (minNodeVersion == null) {
      return true;
    }
    LOG.debug("Checking Node.js version");
    try {
      String actualVersion = getVersion(nodeExecutable);
      return checkVersion(actualVersion, minNodeVersion);
    } catch (Exception e) {
      LOG.error("Failed to get Node.js version.", e);
      return false;
    }
  }

  @VisibleForTesting
  static boolean checkVersion(String actualVersion, int requiredVersion) {
    Matcher versionMatcher = NODEJS_VERSION_PATTERN.matcher(actualVersion);
    if (versionMatcher.lookingAt()) {
      int major = Integer.parseInt(versionMatcher.group(1));
      if (major < requiredVersion) {
        LOG.error("Only Node.js v{} or later is supported, got {}.", requiredVersion, actualVersion);
        return false;
      }
    } else {
      LOG.error("Failed to parse Node.js version, got {}.", actualVersion);
      return false;
    }

    LOG.debug("Using Node.js {}.", actualVersion);
    return true;
  }

  private String getVersion(String nodeExecutable) {
    StringBuilder output = new StringBuilder();
    NodeCommand nodeCommand = new NodeCommand(processWrapper, nodeExecutable, singletonList("-v"), null, emptyList(), output::append, LOG::error);
    nodeCommand.start();
    int exitValue = nodeCommand.waitFor();
    if (exitValue != 0) {
      throw new NodeCommandException("Failed to run Node.js with -v to determine the version");
    }
    return output.toString();
  }

  private static String retrieveNodeExecutableFromConfig(@Nullable Configuration configuration) {
    if (configuration == null) {
      return NODE_EXECUTABLE_DEFAULT;
    }
    for (String property : NODE_EXECUTABLE_PROPERTIES) {
      Optional<String> nodeExecutableOptional = configuration.get(property);
      if (nodeExecutableOptional.isPresent()) {
        String nodeExecutable = nodeExecutableOptional.get();
        File file = new File(nodeExecutable);
        if (file.exists()) {
          // TODO add deprecation warning when not using sonar.nodejs.executable
          LOG.info("Using Node.js executable {} from property {}.", file.getAbsoluteFile(), property);
          return nodeExecutable;
        }
        LOG.warn("Provided Node.js executable file does not exist. Property: {}. File: {}", property, file.getAbsoluteFile());
      }
    }

    return NODE_EXECUTABLE_DEFAULT;
  }
}