/*
 * Copyright (c) 2015, salesforce.com, inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided
 * that the following conditions are met:
 *
 *    Redistributions of source code must retain the above copyright notice, this list of conditions and the
 *    following disclaimer.
 *
 *    Redistributions in binary form must reproduce the above copyright notice, this list of conditions and
 *    the following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 *    Neither the name of salesforce.com, inc. nor the names of its contributors may be used to endorse or
 *    promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.salesforce.dataloader.process;

/**
 * @author Lexi Viripaeff
 * @input DataLoaderRunner -------------- @ * ----------------
 */

import com.salesforce.dataloader.controller.Controller;
import com.salesforce.dataloader.exception.ControllerInitializationException;
import com.salesforce.dataloader.ui.UIUtils;
import com.salesforce.dataloader.util.AppUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import org.apache.logging.log4j.Logger;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Map;

import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.salesforce.dataloader.client.HttpClientTransport;
import com.salesforce.dataloader.config.Config;

public class DataLoaderRunner extends Thread {
    private static final String LOCAL_SWT_DIR = "./target/";
    private static final String PATH_SEPARATOR = System.getProperty("path.separator");
    private static final String FILE_SEPARATOR = System.getProperty("file.separator");
    private static Map<String, String> argNameValuePair;
    private static Logger logger;

    private static boolean isBatchMode(String[] args) {   
        Map<String, String> argMap = Controller.getArgMapFromArgArray(args);
        return argMap.containsKey(Config.CLI_OPTION_RUN_MODE) ?
                Config.RUN_MODE_BATCH_VAL.equalsIgnoreCase(argMap.get(Config.CLI_OPTION_RUN_MODE)) : false;
    }

    public void run() {
        // called just before the program closes
        HttpClientTransport.closeConnections();
    }

    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new DataLoaderRunner());
        if (isBatchMode(args)) {
            try {
                ProcessRunner.runBatchMode(args, null);
            } catch (Throwable t) {
                ProcessRunner.logErrorAndExitProcess("Unable to run process", t);
            }
        } else {
            /* Run in the UI mode, get the controller instance with batchMode == false */
            argNameValuePair = Controller.getArgMapFromArgArray(args);
            logger = Controller.getLogger(argNameValuePair, DataLoaderRunner.class);
            if (argNameValuePair.containsKey(Config.CLI_OPTION_SWT_NATIVE_LIB_IN_JAVA_LIB_PATH) 
                && "true".equalsIgnoreCase(argNameValuePair.get(Config.CLI_OPTION_SWT_NATIVE_LIB_IN_JAVA_LIB_PATH))){
                try {
                    String defaultBrowser = System.getProperty("org.eclipse.swt.browser.DefaultType");
                    if (defaultBrowser == null) {
                        logger.debug("org.eclipse.swt.browser.DefaultType not set for UI mode on Windows");
                    } else {
                        logger.debug("org.eclipse.swt.browser.DefaultType set to " + defaultBrowser + " for UI mode on Windows");
                    }
                    Controller controller = Controller.getInstance(Config.RUN_MODE_UI_VAL, args);
                    controller.createAndShowGUI();
                } catch (ControllerInitializationException e) {
                    UIUtils.errorMessageBox(new Shell(new Display()), e);
                }
            } else { // SWT_NATIVE_LIB_IN_JAVA_LIB_PATH not set
                rerunWithSWTNativeLib(args);
            }
        }
    }
    
    private static void rerunWithSWTNativeLib(String[] args) {
        String javaExecutablePath = null;
        try {
            javaExecutablePath = ProcessHandle.current()
                .info()
                .command()
                .orElseThrow();
        } catch (Exception e) {
            // fail silently
        }
        if (javaExecutablePath == null) {
            javaExecutablePath = System.getProperty("java.home")
                    + FILE_SEPARATOR + "bin" + FILE_SEPARATOR + "java";
        }
        // java command is the first argument
        ArrayList<String> jvmArgs = new ArrayList<String>(128);
        logger.debug("java executable path: " + javaExecutablePath);
        jvmArgs.add(javaExecutablePath);

        // JVM options
        // set -XstartOnFirstThread for MacOS
        String osName = System.getProperty("os.name").toLowerCase();
        if ((osName.contains("mac")) || (osName.startsWith("darwin"))) {
            jvmArgs.add("-XstartOnFirstThread");
            logger.debug("added JVM arg -XstartOnFirstThread");
        }
        
        // set JVM arguments
        // set library path for native libs of SWT
        String librarypath = System.getProperty("java.library.path");
        File swtJarFileHandle = getSWTJarFileHandle();
        if (swtJarFileHandle == null) {
            logger.error("Unable to find SWT jar for " 
                    + System.getProperty("os.name") + " : "
                    + System.getProperty("os.arch"));
            System.exit(-1);
        }
        String swtDir = swtJarFileHandle.getParent();
        if (librarypath != null && !librarypath.isBlank()) {
            librarypath = swtDir + PATH_SEPARATOR + librarypath;
        } else {
            librarypath = swtDir;
        }
        jvmArgs.add("-Djava.library.path=" + librarypath);
        logger.debug("set java.library.path=" + librarypath);
        
        // add JVM arguments specified in the command line
        jvmArgs.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());

        // set classpath
        String classpath = System.getProperty("java.class.path");
        String SWTJarPath = swtJarFileHandle.getAbsolutePath();
        if (classpath != null && !classpath.isBlank()) {
            classpath = SWTJarPath + PATH_SEPARATOR + classpath;
        } else {
            classpath = SWTJarPath;
        }
        jvmArgs.add("-cp");
        jvmArgs.add(classpath);
        logger.debug("set java.class.path=" + classpath);
        
        // specify name of the class with main method
        jvmArgs.add(DataLoaderRunner.class.getName());
        logger.debug("added class to execute - " + DataLoaderRunner.class.getName());
        
        // specify application arguments
        logger.debug("added following arguments:");
        for (int i = 0; i < args.length; i++) {
          jvmArgs.add(args[i]);
          logger.debug("    " + args[i]);
        }
        
        // add the argument to indicate that JAVA_LIB_PATH has the directory containing SWT native libraries
        jvmArgs.add(Config.CLI_OPTION_SWT_NATIVE_LIB_IN_JAVA_LIB_PATH + "=true");
        logger.debug("    " + Config.CLI_OPTION_SWT_NATIVE_LIB_IN_JAVA_LIB_PATH + "=true");
        ProcessBuilder processBuilder = new ProcessBuilder(jvmArgs);
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            InputStream is = process.getInputStream();
            InputStreamReader isr = new InputStreamReader(is);
            BufferedReader br = new BufferedReader(isr);
            String line;

            while ((line = br.readLine()) != null) {
              System.out.println(line);
            }

            process.waitFor();
        } catch (IOException | InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
    
    
    private static String constructSwtJarNameFromOSAndArch(boolean skipOSAndArch) {
        String swtJarPrefix = "swt";
        String swtJarSuffix = "*.jar";
        
        String osNameStr = getOSName();
        String archStr = System.getProperty("os.arch");

        if (archStr.toLowerCase().contains("amd")) {
            archStr = "x86_64";
        }
        
        // e.g. swtwin32_x86_64*.jar or swtmac_aarch64*.jar
        String pathStr = swtJarPrefix 
                + (skipOSAndArch ? "" : osNameStr + "_" + archStr)
                + swtJarSuffix;
        
        return pathStr;
    }

    private static String getOSName() {
        String osNameProperty = System.getProperty("os.name");
    
        if (osNameProperty == null) {
            throw new RuntimeException("os.name property is not set");
        }
        else {
            osNameProperty = osNameProperty.toLowerCase();
        }
    
        if (osNameProperty.contains("win")) {
           return "win32";
        } else if (osNameProperty.contains("mac")) {
           return "mac";
        } else if (osNameProperty.contains("linux") || osNameProperty.contains("nix")) {
           return "linux";
        } else {
           throw new RuntimeException("Unknown OS name: " + osNameProperty);
        }
    }
    
    private static File getSWTJarFileHandleFromWildcard(String parentDirStr, String childFileStr) {
        if (parentDirStr == null || parentDirStr.isBlank() || childFileStr == null || childFileStr.isBlank()) {
            return null;
        }
        if (parentDirStr.contains("*")) {
            // path to the file has a wildcard. Assume that it is present only at the parent directory level
            String[] subpaths = parentDirStr.split("\\*");
            File grandparentDir = new File(subpaths[0]);
            File[] possibleParentDirs = grandparentDir.listFiles();
            for (File possibleParentDir : possibleParentDirs) {
                if (possibleParentDir.isDirectory()) {
                    File possibleSWTJarFile = getSWTJarFileHandleFromWildcard(
                            possibleParentDir.getAbsolutePath(), childFileStr);
                    if (possibleSWTJarFile != null) {
                        return possibleSWTJarFile;
                    }
                }
            }            
        }
        File parentDir = new File(parentDirStr);
        if (!parentDir.exists()) {
            return null;
        }
        FileFilter fileFilter = new WildcardFileFilter(childFileStr);
        File[] files = parentDir.listFiles(fileFilter);
        if (files != null && files.length > 0) {
            return files[0];
        }
        return null;
    }
    
    private static File getSWTJarFileHandle() {
        String[]parentDirOfSWTDirArray = 
                    {AppUtil.getDirContainingClassJar(DataLoaderRunner.class)
                     , "."
                     , ".."
                     , LOCAL_SWT_DIR
                    };
        Boolean[] skipOSAndArchInFileNameConstructionArray = {Boolean.FALSE, Boolean.TRUE};
        for (String parentDirOfSwtDir : parentDirOfSWTDirArray) {
            if (parentDirOfSwtDir == null) {
                continue;
            }
            for (Boolean skipOSAndArchVal : skipOSAndArchInFileNameConstructionArray) {
                String swtDirPathStr = parentDirOfSwtDir + "/*";
                String swtJarPathWithParentDirWildcard = constructSwtJarNameFromOSAndArch(skipOSAndArchVal);
                File swtJarFileHandle = getSWTJarFileHandleFromWildcard(swtDirPathStr, swtJarPathWithParentDirWildcard);
                if (swtJarFileHandle != null) {
                    logger.debug("Found SWT jar at " + swtJarFileHandle.getAbsolutePath());
                    return swtJarFileHandle;
                }
            }
        }

        // try to get it from the CLASSPATH
        Map<String, String>envVars = System.getenv();
        String classPathStr = envVars.get("CLASSPATH");
        File swtJarFileHandle = getSWTJarFileFromClassPath(classPathStr);
        if (swtJarFileHandle != null) {
            return swtJarFileHandle;
        }
        classPathStr = System.getProperty("java.class.path");
        return getSWTJarFileFromClassPath(classPathStr);
    }
    
    private static File getSWTJarFileFromClassPath(String classPathStr) {
        if (classPathStr == null) {
            return null;
        }
        logger.debug("CLASSPATH = " + classPathStr);
        if (classPathStr.toLowerCase().contains("swt")) {
            String[] pathValues = classPathStr.split(PATH_SEPARATOR);
            for (String pathVal : pathValues) {
                if (pathVal.toLowerCase().contains("swt")) {
                    File swtFile = new File(pathVal);
                    if (swtFile.exists()) {
                        return swtFile;
                    }
                }
            }
        }
        return null;
    }
}
