/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Luke Hutchison
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.lukehutch.fastclasspathscanner.issues.issue37;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class Issue37Test {

    @Issue37Annotation
    public Issue37Test() {
    }

    @Issue37Annotation
    @Test
    public void issue37Test() {
        final List<String> methodNames = new ArrayList<>();
        final String pkg = Issue37Test.class.getPackage().getName();
        new FastClasspathScanner(pkg) //
                .enableMethodAnnotationIndexing() //
                .matchClassesWithMethodAnnotation(Issue37Annotation.class, (matchingClass, matchingMethod) -> {
                    methodNames.add(matchingMethod.getName());
                }) //
                .scan();
        // For some reason in the JRE, constructor names are fully-qualified, method names are not :~)
        assertThat(methodNames).containsExactly(Issue37Test.class.getName(), "issue37Test");
    }

    public void unannotatedMethod() {
    }
}