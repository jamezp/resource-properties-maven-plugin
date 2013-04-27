package org.jboss.plugin;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.filtering.MavenResourcesFiltering;
import org.codehaus.plexus.util.DirectoryScanner;

/**
 * Replaces expression properties with the value specified.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@Mojo(name = "replace-properties", defaultPhase = LifecyclePhase.COMPILE)
@Execute(phase = LifecyclePhase.COMPILE)
public class ReplacePropertiesMojo extends AbstractMojo {

    /**
     * The character encoding
     */
    @Parameter(property = "encoding", defaultValue = "${project.build.sourceEncoding}")
    private String encoding;

    /**
     * The output directory where resources should be processed
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
    private File outputDirectory;

    /**
     * File patterns to include when processing
     */
    @Parameter
    private String[] excludes;

    /**
     * File patterns to exclude when processing
     */
    @Parameter
    private String[] includes;

    /**
     * The properties to be used to replace expression values
     */
    @Parameter
    private Map<String, String> properties;

    /**
     * A properties file to be used for expression replacement values
     */
    @Parameter(alias = "properties-file", property = "resources.properties-file")
    private File propertiesFile;

    @Parameter(alias = "include-project-properties", defaultValue = "true", property = "resources.include-project-properties")
    private boolean includeProjectProperties;

    @Parameter(alias = "include-system-properties", defaultValue = "true", property = "resources.include-system-properties")
    private boolean includeSystemProperties;

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", required = true, readonly = true)
    private MavenSession session;

    @Component(role = MavenResourcesFiltering.class, hint = "default")
    protected MavenResourcesFiltering mavenResourcesFiltering;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final Log log = getLog();
        final List<File> resourceFiles = new ArrayList<File>();
        for (Object o : project.getResources()) {
            final Resource resource = (Resource) o;
            final File dir = new File(resource.getDirectory());
            if (dir.exists()) {
                resourceFiles.addAll(getFiles(dir, outputDirectory));
            }
        }
        final Properties properties;
        try {
            properties = getProperties();
        } catch (IOException e) {
            throw new MojoFailureException("Could load load properties", e);
        }
        for (File file : resourceFiles) {
            // Process properties files
            File tempFile = null;
            BufferedReader reader = null;
            PrintWriter tempWriter = null;
            try {
                tempFile = File.createTempFile(file.getName(), ".tmp");
                reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), encoding));
                tempWriter = new PrintWriter(tempFile, encoding);
                try {
                    boolean changed = false;
                    String line;
                    while ((line = reader.readLine()) != null) {
                        final String resolvedValue = Expressions.resolveExpression(properties, line);
                        if (!line.equals(resolvedValue)) {
                            changed = true;
                            log.info(String.format("%nOld Value: '%s'%nNew Value: '%s'%n", line, resolvedValue));
                        }
                        tempWriter.println(resolvedValue);
                    }
                    reader.close();
                    tempWriter.close();
                    // Replace the file
                    if (changed) {
                        file.delete();
                        // TODO (jrp) should we throw an exception?
                        if (!copyFile(tempFile, file))
                            log.error("Could not rename file: " + tempFile.getAbsolutePath());
                    }
                } catch (IOException e) {
                    throw new MojoFailureException("Could not process resource: " + file.getAbsolutePath(), e);
                }
            } catch (IOException e) {
                throw new MojoFailureException("Could not process resource: " + file.getAbsolutePath(), e);
            } finally {
                safeClose(reader);
                safeClose(tempWriter);
                tempFile.delete();
            }
        }
    }

    private Properties getProperties() throws IOException {
        final Properties result = new Properties();
        if (includeSystemProperties) {
            final Properties properties = System.getProperties();
            for (String key : properties.stringPropertyNames()) {
                result.setProperty(key, properties.getProperty(key));
            }
        }
        if (includeProjectProperties) {
            final Properties properties = project.getProperties();
            for (String key : properties.stringPropertyNames()) {
                result.setProperty(key, properties.getProperty(key));
            }
        }
        if (properties != null) {
            for (String key : properties.keySet()) {
                result.setProperty(key, properties.get(key));
            }
        }
        // Load properties from the properties file
        if (propertiesFile != null) {
            result.load(new InputStreamReader(new FileInputStream(propertiesFile), encoding));
        }
        return result;
    }

    private List<File> getFiles(final File searchDir, final File targetDir) {
        final List<File> result = new ArrayList<File>();
        final DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir(searchDir);
        scanner.setIncludes(includes);
        scanner.setExcludes(excludes);
        scanner.scan();
        for (String filename : scanner.getIncludedFiles()) {
            final File targetFile = new File(targetDir, filename);
            if (targetFile.exists()) {
                result.add(targetFile);
            }
        }
        return result;
    }


    static void safeClose(final Closeable closeable) {
        if (closeable != null) try {
            closeable.close();
        } catch (Exception e) {
            // no-op
        }
    }

    /**
     * Copies the source file to the destination file.
     *
     * @param srcFile    the file to copy
     * @param targetFile the target file
     *
     * @return {@code true} if the file was successfully copied, {@code false} if the copy failed or was incomplete
     *
     * @throws java.io.IOException if an IO error occurs copying the file
     */
    static boolean copyFile(final File srcFile, final File targetFile) throws IOException {
        FileInputStream in = null;
        FileOutputStream out = null;
        FileChannel inChannel = null;
        FileChannel outChannel = null;
        try {
            in = new FileInputStream(srcFile);
            inChannel = in.getChannel();
            out = new FileOutputStream(targetFile);
            outChannel = out.getChannel();
            long bytesTransferred = 0;
            while (bytesTransferred < inChannel.size()) {
                bytesTransferred += inChannel.transferTo(0, inChannel.size(), outChannel);
            }
        } finally {
            safeClose(outChannel);
            safeClose(out);
            safeClose(inChannel);
            safeClose(in);
        }
        return srcFile.length() == targetFile.length();
    }

}
