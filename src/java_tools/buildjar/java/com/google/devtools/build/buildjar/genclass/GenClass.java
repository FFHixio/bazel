// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.buildjar.genclass;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.buildjar.jarhelper.JarCreator;
import com.google.devtools.build.buildjar.proto.JavaCompilation.CompilationUnit;
import com.google.devtools.build.buildjar.proto.JavaCompilation.Manifest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * GenClass post-processes the output of a Java compilation, and produces a jar containing only the
 * class files for sources that were generated by annotation processors.
 */
public class GenClass {

  /** Recursively delete a directory. */
  private static void deleteTree(Path directory) throws IOException {
    if (directory.toFile().exists()) {
      Files.walkFileTree(
          directory,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Files.delete(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                throws IOException {
              Files.delete(dir);
              return FileVisitResult.CONTINUE;
            }
          });
    }
  }

  public static void main(String[] args) throws IOException {
    GenClassOptions options = GenClassOptionsParser.parse(Arrays.asList(args));
    Manifest manifest = readManifest(options.manifest());
    Path tempDir = Files.createTempDirectory("tmp");
    Files.createDirectories(tempDir);
    extractGeneratedClasses(options.classJar(), manifest, tempDir);
    writeOutputJar(tempDir, options);
    deleteTree(tempDir);
  }

  /** Reads the compilation manifest. */
  private static Manifest readManifest(Path path) throws IOException {
    Manifest manifest;
    try (InputStream inputStream = Files.newInputStream(path)) {
      manifest = Manifest.parseFrom(inputStream);
    }
    return manifest;
  }

  /**
   * For each top-level class in the compilation matching the given predicate, determine the path
   * prefix of classes corresponding to that compilation unit.
   */
  @VisibleForTesting
  static ImmutableSet<String> getPrefixes(Manifest manifest, Predicate<CompilationUnit> p) {
    return manifest
        .getCompilationUnitList()
        .stream()
        .filter(p)
        .flatMap(unit -> getUnitPrefixes(unit))
        .collect(toImmutableSet());
  }

  /**
   * Prefixes are used to correctly handle inner classes, e.g. the top-level class "c.g.Foo" may
   * correspond to "c/g/Foo.class" and also "c/g/Foo$Inner.class" or "c/g/Foo$0.class".
   */
  private static Stream<String> getUnitPrefixes(CompilationUnit unit) {
    String pkg;
    if (unit.hasPkg()) {
      pkg = unit.getPkg().replace('.', '/') + "/";
    } else {
      pkg = "";
    }
    return unit.getTopLevelList().stream().map(toplevel -> pkg + toplevel);
  }

  /**
   * Unzip all the class files that correspond to annotation processor- generated sources into the
   * temporary directory.
   */
  private static void extractGeneratedClasses(Path classJar, Manifest manifest, Path tempDir)
      throws IOException {
    ImmutableSet<String> generatedFilePrefixes =
        getPrefixes(manifest, unit -> unit.getGeneratedByAnnotationProcessor());
    ImmutableSet<String> userWrittenFilePrefixes =
        getPrefixes(manifest, unit -> !unit.getGeneratedByAnnotationProcessor());
    try (JarFile jar = new JarFile(classJar.toFile())) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        String name = entry.getName();
        if (!name.endsWith(".class")) {
          continue;
        }
        String className = name.substring(0, name.length() - ".class".length());
        if (prefixesContains(generatedFilePrefixes, className)
            // Assume that prefixes that don't correspond to a known hand-written source are
            // generated.
            || !prefixesContains(userWrittenFilePrefixes, className)) {
          Files.createDirectories(tempDir.resolve(name).getParent());
          // InputStream closing: JarFile extends ZipFile, and ZipFile.close() will close all of the
          // input streams previously returned by invocations of the getInputStream method.
          // See https://docs.oracle.com/javase/8/docs/api/java/util/zip/ZipFile.html#close--
          Files.copy(jar.getInputStream(entry), tempDir.resolve(name));
        }
      }
    }
  }

  /**
   * We want to include inner classes for generated source files, but a class whose name contains
   * '$' isn't necessarily an inner class. Check whether any prefix of the class name that ends with
   * '$' matches one of the top-level class names.
   */
  private static boolean prefixesContains(ImmutableSet<String> prefixes, String className) {
    if (prefixes.contains(className)) {
      return true;
    }
    for (int i = className.indexOf('$'); i != -1; i = className.indexOf('$', i + 1)) {
      if (prefixes.contains(className.substring(0, i))) {
        return true;
      }
    }
    return false;
  }

  /** Writes the generated class files to the output jar. */
  private static void writeOutputJar(Path tempDir, GenClassOptions options) throws IOException {
    JarCreator output = new JarCreator(options.outputJar().toString());
    output.setCompression(true);
    output.setNormalize(true);
    output.addDirectory(tempDir);
    output.execute();
  }
}
