/*
 * This file is part of dependency-check-core.
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
 *
 * Copyright (c) 2015 Institute for Defense Analyses. All Rights Reserved.
 */
package org.owasp.dependencycheck.analyzer;

import org.owasp.dependencycheck.Engine;
import org.owasp.dependencycheck.analyzer.exception.AnalysisException;
import org.owasp.dependencycheck.dependency.Dependency;
import org.owasp.dependencycheck.utils.FileFilterBuilder;
import org.owasp.dependencycheck.utils.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Used to analyze Ruby Bundler Gemspec.lock files utilizing the 3rd party bundle-audit tool.
 *
 * @author Dale Visser <dvisser@ida.org>
 */
public class RubyBundleAuditAnalyzer extends AbstractFileTypeAnalyzer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RubyBundleAuditAnalyzer.class);

    /**
     * The name of the analyzer.
     */
    private static final String ANALYZER_NAME = "Ruby Bundle Audit Analyzer";

    /**
     * The phase that this analyzer is intended to run in.
     */
    private static final AnalysisPhase ANALYSIS_PHASE = AnalysisPhase.INFORMATION_COLLECTION;

    private static final FileFilter FILTER =
            FileFilterBuilder.newInstance().addFilenames("Gemfile.lock").build();

    /**
     * @return a filter that accepts files named Rakefile or matching the glob pattern, *.gemspec
     */
    @Override
    protected FileFilter getFileFilter() {
        return FILTER;
    }

    /**
     * Launch bundle-audit.
     *
     * @return a handle to the process
     */
    private Process launchBundleAudit(File folder) throws AnalysisException {
        if (!folder.isDirectory()){
            throw new AnalysisException(String.format("%s should have been a directory.", folder.getAbsolutePath()));
        }
        final List<String> args = new ArrayList<String>();
        final String bundleAuditPath = Settings.getString(Settings.KEYS.ANALYZER_BUNDLE_AUDIT_PATH);
        args.add(null == bundleAuditPath ? "bundle-audit" : bundleAuditPath);
        args.add("check");
        final ProcessBuilder builder = new ProcessBuilder(args);
        builder.directory(folder);
        try {
            return builder.start();
        } catch (IOException ioe) {
            throw new AnalysisException("bundle-audit failure", ioe);
        }
    }

    /**
     * Initialize the analyzer. In this case, extract GrokAssembly.exe to a temporary location.
     *
     * @throws Exception if anything goes wrong
     */
    @Override
    public void initializeFileTypeAnalyzer() throws Exception {
        // Now, need to see if bundle-audit actually runs from this location.
        try {
            Process process = launchBundleAudit(Settings.getTempDirectory());
            int exitValue = process.waitFor();
            if (0 == exitValue) {
                LOGGER.warn("Unexpected exit code from bundle-audit process. Disabling %s: %d", ANALYZER_NAME, exitValue);
                setEnabled(false);
            } else {
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), "UTF-8"));
                    if (!reader.ready()) {
                        LOGGER.warn("Bundle-audit error stream unexpectedly not ready. Disabling " + ANALYZER_NAME);
                        setEnabled(false);
                    } else {
                        final String line = reader.readLine();
                        if (!line.contains("Errno::ENOENT")) {
                            LOGGER.warn("Unexpected bundle-audit output. Disabling %s: %s", ANALYZER_NAME, line);
                            setEnabled(false);
                        }
                    }
                } finally {
                    if (null != reader) {
                        reader.close();
                    }
                }
            }
        } catch (AnalysisException ae) {
            LOGGER.warn("Exception while trying to launch bundle-audit. Disabling " + ANALYZER_NAME, ae.getCause());
            setEnabled(false);
        }
    }

    /**
     * Returns the name of the analyzer.
     *
     * @return the name of the analyzer.
     */
    @Override
    public String getName() {
        return ANALYZER_NAME;
    }

    /**
     * Returns the phase that the analyzer is intended to run in.
     *
     * @return the phase that the analyzer is intended to run in.
     */
    @Override
    public AnalysisPhase getAnalysisPhase() {
        return ANALYSIS_PHASE;
    }

    /**
     * Returns the key used in the properties file to reference the analyzer's enabled property.
     *
     * @return the analyzer's enabled property setting key
     */
    @Override
    protected String getAnalyzerEnabledSettingKey() {
        return Settings.KEYS.ANALYZER_RUBY_GEMSPEC_ENABLED;
    }

    @Override
    protected void analyzeFileType(Dependency dependency, Engine engine)
            throws AnalysisException {
        final File parentFile = dependency.getActualFile().getParentFile();
        final Process process = launchBundleAudit(parentFile);
        try {
            process.waitFor();
        } catch (InterruptedException ie) {
            throw new AnalysisException("bundle-audit process interrupted", ie);
        }
        BufferedReader rdr = null;
        try {
            rdr = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            while (rdr.ready()) {
                final String nextLine = rdr.readLine();
                if (null == nextLine) {
                    break;
                }
                LOGGER.info(String.format("bundle-audit (%s): %s", parentFile.getName(), nextLine));
            }
        } catch (IOException ioe) {
            LOGGER.warn("bundle-audit failure", ioe);
        } finally {
            if (null != rdr) {
                try {
                    rdr.close();
                } catch (IOException ioe) {
                    LOGGER.warn("bundle-audit close failure", ioe);
                }
            }
        }

    }
}
