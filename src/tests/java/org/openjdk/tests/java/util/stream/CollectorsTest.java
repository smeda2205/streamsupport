/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.tests.java.util.stream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

import java8.lang.Doubles;
import java8.lang.Integers;
import java8.lang.Longs;

import java8.util.Comparators;
import java8.util.Optional;
import java8.util.StringJoiner;
import java8.util.function.BinaryOperator;
import java8.util.function.Function;
import java8.util.function.Predicate;
import java8.util.function.Predicates;
import java8.util.function.Supplier;
import java8.util.stream.Collector;
import java8.util.stream.Collectors;
import java8.util.stream.LambdaTestHelpers;
import java8.util.stream.OpTestCase;
import java8.util.stream.Stream;
import java8.util.stream.StreamSupport;
import java8.util.stream.StreamOpFlagTestHelper;
import java8.util.stream.StreamTestDataProvider;
import java8.util.stream.TestData;

import org.testng.annotations.Test;

import static java8.util.stream.Collectors.collectingAndThen;
import static java8.util.stream.Collectors.flatMapping;
import static java8.util.stream.Collectors.groupingBy;
import static java8.util.stream.Collectors.groupingByConcurrent;
import static java8.util.stream.Collectors.mapping;
import static java8.util.stream.Collectors.partitioningBy;
import static java8.util.stream.Collectors.reducing;
import static java8.util.stream.Collectors.toCollection;
import static java8.util.stream.Collectors.toConcurrentMap;
import static java8.util.stream.Collectors.toList;
import static java8.util.stream.Collectors.toMap;
import static java8.util.stream.Collectors.toSet;
import static java8.util.stream.LambdaTestHelpers.assertContents;
import static java8.util.stream.LambdaTestHelpers.assertContentsUnordered;
import static java8.util.stream.LambdaTestHelpers.mDoubler;

/*
 * @test
 * @bug 8071600
 * @summary Test for collectors.
 */
public class CollectorsTest extends OpTestCase {

    private static abstract class CollectorAssertion<T, U> {
        abstract void assertValue(U value,
                                  Supplier<Stream<T>> source,
                                  boolean ordered) throws Exception;
    }

    static class MappingAssertion<T, V, R> extends CollectorAssertion<T, R> {
        private final Function<T, V> mapper;
        private final CollectorAssertion<V, R> downstream;

        MappingAssertion(Function<T, V> mapper, CollectorAssertion<V, R> downstream) {
            this.mapper = mapper;
            this.downstream = downstream;
        }

        @Override
        void assertValue(R value, Supplier<Stream<T>> source, boolean ordered) throws Exception {
            downstream.assertValue(value,
                                   () -> source.get().map(mapper::apply),
                                   ordered);
        }
    }

    static class FlatMappingAssertion<T, V, R> extends CollectorAssertion<T, R> {
        private final Function<T, Stream<V>> mapper;
        private final CollectorAssertion<V, R> downstream;

        FlatMappingAssertion(Function<T, Stream<V>> mapper,
                             CollectorAssertion<V, R> downstream) {
            this.mapper = mapper;
            this.downstream = downstream;
        }

        @Override
        void assertValue(R value, Supplier<Stream<T>> source, boolean ordered) throws Exception {
            downstream.assertValue(value,
                                   () -> source.get().flatMap(mapper::apply),
                                   ordered);
        }
    }

    static class GroupingByAssertion<T, K, V, M extends Map<K, ? extends V>> extends CollectorAssertion<T, M> {
        private final Class<? extends Map> clazz;
        private final Function<T, K> classifier;
        private final CollectorAssertion<T,V> downstream;

        GroupingByAssertion(Function<T, K> classifier, Class<? extends Map> clazz,
                            CollectorAssertion<T, V> downstream) {
            this.clazz = clazz;
            this.classifier = classifier;
            this.downstream = downstream;
        }

        @Override
        void assertValue(M map,
                         Supplier<Stream<T>> source,
                         boolean ordered) throws Exception {
            if (!clazz.isAssignableFrom(map.getClass()))
                fail(String.format("Class mismatch in GroupingByAssertion: %s, %s", clazz, map.getClass()));
            assertContentsUnordered(map.keySet(), source.get().map(classifier).collect(toSet()));
            for (Map.Entry<K, ? extends V> entry : map.entrySet()) {
                K key = entry.getKey();
                downstream.assertValue(entry.getValue(),
                                       () -> source.get().filter(e -> classifier.apply(e).equals(key)),
                                       ordered);
            }
        }
    }

    static class ToMapAssertion<T, K, V, M extends Map<K, V>> extends CollectorAssertion<T, M> {
        private final Class<? extends Map> clazz;
        private final Function<T, K> keyFn;
        private final Function<T, V> valueFn;
        private final BinaryOperator<V> mergeFn;

        ToMapAssertion(Function<T, K> keyFn,
                       Function<T, V> valueFn,
                       BinaryOperator<V> mergeFn,
                       Class<? extends Map> clazz) {
            this.clazz = clazz;
            this.keyFn = keyFn;
            this.valueFn = valueFn;
            this.mergeFn = mergeFn;
        }

        @Override
        void assertValue(M map, Supplier<Stream<T>> source, boolean ordered) throws Exception {
            if (!clazz.isAssignableFrom(map.getClass()))
                fail(String.format("Class mismatch in ToMapAssertion: %s, %s", clazz, map.getClass()));
            Set<K> uniqueKeys = source.get().map(keyFn).collect(toSet());
            assertEquals(uniqueKeys, map.keySet());
            source.get().forEach(t -> {
                K key = keyFn.apply(t);
                V v = source.get()
                            .filter(e -> key.equals(keyFn.apply(e)))
                            .map(valueFn)
                            .reduce(mergeFn)
                            .get();
                assertEquals(map.get(key), v);
            });
        }
    }

    static class PartitioningByAssertion<T, D> extends CollectorAssertion<T, Map<Boolean, D>> {
        private final Predicate<T> predicate;
        private final CollectorAssertion<T, D> downstream;

        PartitioningByAssertion(Predicate<T> predicate, CollectorAssertion<T, D> downstream) {
            this.predicate = predicate;
            this.downstream = downstream;
        }

        @Override
        void assertValue(Map<Boolean, D> map,
                         Supplier<Stream<T>> source,
                         boolean ordered) throws Exception {
            if (!Map.class.isAssignableFrom(map.getClass()))
                fail(String.format("Class mismatch in PartitioningByAssertion: %s", map.getClass()));
            assertEquals(2, map.size());
            downstream.assertValue(map.get(true), () -> source.get().filter(predicate), ordered);
            downstream.assertValue(map.get(false), () -> source.get().filter(Predicates.negate(predicate)), ordered);
        }
    }

    static class ToListAssertion<T> extends CollectorAssertion<T, List<T>> {
        @Override
        void assertValue(List<T> value, Supplier<Stream<T>> source, boolean ordered)
                throws Exception {
            if (!List.class.isAssignableFrom(value.getClass()))
                fail(String.format("Class mismatch in ToListAssertion: %s", value.getClass()));
            Stream<T> stream = source.get();
            List<T> result = new ArrayList<>();
            for (Iterator<T> it = stream.iterator(); it.hasNext(); ) // avoid capturing result::add
                result.add(it.next());
            if (StreamOpFlagTestHelper.isStreamOrdered(stream) && ordered)
                assertContents(value, result);
            else
                assertContentsUnordered(value, result);
        }
    }

    static class ToCollectionAssertion<T> extends CollectorAssertion<T, Collection<T>> {
        private final Class<? extends Collection> clazz;
        private final boolean targetOrdered;

        ToCollectionAssertion(Class<? extends Collection> clazz, boolean targetOrdered) {
            this.clazz = clazz;
            this.targetOrdered = targetOrdered;
        }

        @Override
        void assertValue(Collection<T> value, Supplier<Stream<T>> source, boolean ordered)
                throws Exception {
            if (!clazz.isAssignableFrom(value.getClass()))
                fail(String.format("Class mismatch in ToCollectionAssertion: %s, %s", clazz, value.getClass()));
            Stream<T> stream = source.get();
            Collection<T> result = clazz.newInstance();
            for (Iterator<T> it = stream.iterator(); it.hasNext(); ) // avoid capturing result::add
                result.add(it.next());
            if (StreamOpFlagTestHelper.isStreamOrdered(stream) && targetOrdered && ordered)
                assertContents(value, result);
            else
                assertContentsUnordered(value, result);
        }
    }

    static class ReducingAssertion<T, U> extends CollectorAssertion<T, U> {
        private final U identity;
        private final Function<T, U> mapper;
        private final BinaryOperator<U> reducer;

        ReducingAssertion(U identity, Function<T, U> mapper, BinaryOperator<U> reducer) {
            this.identity = identity;
            this.mapper = mapper;
            this.reducer = reducer;
        }

        @Override
        void assertValue(U value, Supplier<Stream<T>> source, boolean ordered)
                throws Exception {
            Optional<U> reduced = source.get().map(mapper).reduce(reducer);
            if (value == null)
                assertTrue(!reduced.isPresent());
            else if (!reduced.isPresent()) {
                assertEquals(value, identity);
            }
            else {
                assertEquals(value, reduced.get());
            }
        }
    }

    private <T> ResultAsserter<T> mapTabulationAsserter(boolean ordered) {
        return (act, exp, ord, par) -> {
            if (par && (!ordered || !ord)) {
                CollectorsTest.nestedMapEqualityAssertion(act, exp);
            }
            else {
                LambdaTestHelpers.assertContentsEqual(act, exp);
            }
        };
    }

    private<T, M extends Map>
    void exerciseMapCollection(TestData<T, Stream<T>> data,
                               Collector<T, ?, ? extends M> collector,
                               CollectorAssertion<T, M> assertion)
            throws Exception {
        boolean ordered = !collector.characteristics().contains(Collector.Characteristics.UNORDERED);

        M m = withData(data)
                .terminal(s -> s.collect(collector))
                .resultAsserter(mapTabulationAsserter(ordered))
                .exercise();
        assertion.assertValue(m, () -> data.stream(), ordered);

        m = withData(data)
                .terminal(s -> s.unordered().collect(collector))
                .resultAsserter(mapTabulationAsserter(ordered))
                .exercise();
        assertion.assertValue(m, () -> data.stream(), false);
    }

    private static void nestedMapEqualityAssertion(Object o1, Object o2) {
        if (o1 instanceof Map) {
            Map m1 = (Map) o1;
            Map m2 = (Map) o2;
            assertContentsUnordered(m1.keySet(), m2.keySet());
            for (Object k : m1.keySet())
                nestedMapEqualityAssertion(m1.get(k), m2.get(k));
        }
        else if (o1 instanceof Collection) {
            assertContentsUnordered(((Collection) o1), ((Collection) o2));
        }
        else
            assertEquals(o1, o2);
    }

    private<T, R> void assertCollect(TestData.OfRef<T> data,
                                     Collector<T, ?, R> collector,
                                     Function<Stream<T>, R> streamReduction) {
        R check = streamReduction.apply(data.stream());
        withData(data).terminal(s -> s.collect(collector)).expectedResult(check).exercise();
    }

    @Test(dataProvider = "StreamTestData<Integer>", dataProviderClass = StreamTestDataProvider.class)
    public void testReducing(String name, TestData.OfRef<Integer> data) throws Exception {
        assertCollect(data, Collectors.reducing(0, Integers::sum),
                      s -> s.reduce(0, Integers::sum));
        assertCollect(data, Collectors.reducing(Integer.MAX_VALUE, Integers::min),
                      s -> s.min(Integers::compare).orElse(Integer.MAX_VALUE));
        assertCollect(data, Collectors.reducing(Integer.MIN_VALUE, Integers::max),
                      s -> s.max(Integers::compare).orElse(Integer.MIN_VALUE));

        assertCollect(data, Collectors.reducing(Integers::sum),
                      s -> s.reduce(Integers::sum));
        assertCollect(data, Collectors.minBy(Comparators.naturalOrder()),
                      s -> s.min(Integers::compare));
        assertCollect(data, Collectors.maxBy(Comparators.naturalOrder()),
                      s -> s.max(Integers::compare));

        assertCollect(data, Collectors.reducing(0, x -> x * 2, Integers::sum),
                      s -> s.map(x -> x * 2).reduce(0, Integers::sum));

        assertCollect(data, Collectors.summingLong(x -> x * 2L),
                      s -> s.map(x -> x * 2L).reduce(0L, Longs::sum));
        assertCollect(data, Collectors.summingInt(x -> x * 2),
                      s -> s.map(x -> x * 2).reduce(0, Integers::sum));
        assertCollect(data, Collectors.summingDouble(x -> x * 2.0d),
                      s -> s.map(x -> x * 2.0d).reduce(0.0d, Doubles::sum));

        assertCollect(data, Collectors.averagingInt(x -> x * 2),
                      s -> s.mapToInt(x -> x * 2).average().orElse(0));
        assertCollect(data, Collectors.averagingLong(x -> x * 2),
                      s -> s.mapToLong(x -> x * 2).average().orElse(0));
        assertCollect(data, Collectors.averagingDouble(x -> x * 2),
                      s -> s.mapToDouble(x -> x * 2).average().orElse(0));

        // Test explicit Collectors.of
        Collector<Integer, long[], Double> avg2xint = Collectors.of(() -> new long[2],
                                                                   (a, b) -> {
                                                                       a[0] += b * 2;
                                                                       a[1]++;
                                                                   },
                                                                   (a, b) -> {
                                                                       a[0] += b[0];
                                                                       a[1] += b[1];
                                                                       return a;
                                                                   },
                                                                   a -> a[1] == 0 ? 0.0d : (double) a[0] / a[1]);
        assertCollect(data, avg2xint,
                      s -> s.mapToInt(x -> x * 2).average().orElse(0));
    }

    @Test(dataProvider = "StreamTestData<Integer>", dataProviderClass = StreamTestDataProvider.class)
    public void testJoining(String name, TestData.OfRef<Integer> data) throws Exception {
        withData(data)
                .terminal(s -> s.map(Object::toString).collect(Collectors.joining()))
                .expectedResult(join(data, ""))
                .exercise();

        Collector<String, StringBuilder, String> likeJoining = Collectors.of(StringBuilder::new, StringBuilder::append, (sb1, sb2) -> sb1.append(sb2.toString()), StringBuilder::toString);
        withData(data)
                .terminal(s -> s.map(Object::toString).collect(likeJoining))
                .expectedResult(join(data, ""))
                .exercise();

        withData(data)
                .terminal(s -> s.map(Object::toString).collect(Collectors.joining(",")))
                .expectedResult(join(data, ","))
                .exercise();

        withData(data)
                .terminal(s -> s.map(Object::toString).collect(Collectors.joining(",", "[", "]")))
                .expectedResult("[" + join(data, ",") + "]")
                .exercise();

        withData(data)
                .terminal(s -> s.map(Object::toString)
                                .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append)
                                .toString())
                .expectedResult(join(data, ""))
                .exercise();

        withData(data)
                .terminal(s -> s.map(Object::toString)
                                .collect(() -> new StringJoiner(","),
                                         (sj, cs) -> sj.add(cs),
                                         (j1, j2) -> j1.merge(j2))
                                .toString())
                .expectedResult(join(data, ","))
                .exercise();

        withData(data)
                .terminal(s -> s.map(Object::toString)
                                .collect(() -> new StringJoiner(",", "[", "]"),
                                         (sj, cs) -> sj.add(cs),
                                         (j1, j2) -> j1.merge(j2))
                                .toString())
                .expectedResult("[" + join(data, ",") + "]")
                .exercise();
    }

    private<T> String join(TestData.OfRef<T> data, String delim) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (T i : data) {
            if (!first)
                sb.append(delim);
            sb.append(i.toString());
            first = false;
        }
        return sb.toString();
    }

    @Test(dataProvider = "StreamTestData<Integer>", dataProviderClass = StreamTestDataProvider.class)
    public void testSimpleToMap(String name, TestData.OfRef<Integer> data) throws Exception {
        Function<Integer, Integer> keyFn = i -> i * 2;
        Function<Integer, Integer> valueFn = i -> i * 4;

        List<Integer> dataAsList = Arrays.asList(data.stream().toArray(Integer[]::new));
        Set<Integer> dataAsSet = new HashSet<>(dataAsList);

        BinaryOperator<Integer> sum = Integers::sum;
        for (BinaryOperator<Integer> op : Arrays.asList((u, v) -> u,
                                                        (u, v) -> v,
                                                        sum)) {
            try {
                exerciseMapCollection(data, toMap(keyFn, valueFn),
                                      new ToMapAssertion<>(keyFn, valueFn, op, HashMap.class));
                if (dataAsList.size() != dataAsSet.size())
                    fail("Expected IllegalStateException on input with duplicates");
            }
            catch (IllegalStateException e) {
                if (dataAsList.size() == dataAsSet.size())
                    fail("Expected no IllegalStateException on input without duplicates");
            }

            exerciseMapCollection(data, toMap(keyFn, valueFn, op),
                                  new ToMapAssertion<>(keyFn, valueFn, op, HashMap.class));

            exerciseMapCollection(data, toMap(keyFn, valueFn, op, TreeMap::new),
                                  new ToMapAssertion<>(keyFn, valueFn, op, TreeMap.class));
        }

        // For concurrent maps, only use commutative merge functions
        try {
            exerciseMapCollection(data, toConcurrentMap(keyFn, valueFn),
                                  new ToMapAssertion<>(keyFn, valueFn, sum, ConcurrentHashMap.class));
            if (dataAsList.size() != dataAsSet.size())
                fail("Expected IllegalStateException on input with duplicates");
        }
        catch (IllegalStateException e) {
            if (dataAsList.size() == dataAsSet.size())
                fail("Expected no IllegalStateException on input without duplicates");
        }

        exerciseMapCollection(data, toConcurrentMap(keyFn, valueFn, sum),
                              new ToMapAssertion<>(keyFn, valueFn, sum, ConcurrentHashMap.class));

        exerciseMapCollection(data, toConcurrentMap(keyFn, valueFn, sum, ConcurrentSkipListMap::new),
                              new ToMapAssertion<>(keyFn, valueFn, sum, ConcurrentSkipListMap.class));
    }

    @Test(dataProvider = "StreamTestData<Integer>", dataProviderClass = StreamTestDataProvider.class)
    public void testSimpleGroupingBy(String name, TestData.OfRef<Integer> data) throws Exception {
        Function<Integer, Integer> classifier = i -> i % 3;

        // Single-level groupBy
        exerciseMapCollection(data, groupingBy(classifier),
                              new GroupingByAssertion<>(classifier, HashMap.class,
                                                        new ToListAssertion<>()));
        exerciseMapCollection(data, groupingByConcurrent(classifier),
                              new GroupingByAssertion<>(classifier, ConcurrentHashMap.class,
                                                        new ToListAssertion<>()));

        // With explicit constructors
        exerciseMapCollection(data,
                              groupingBy(classifier, TreeMap::new, toCollection(HashSet::new)),
                              new GroupingByAssertion<>(classifier, TreeMap.class,
                                                        new ToCollectionAssertion<Integer>(HashSet.class, false)));
        exerciseMapCollection(data,
                              groupingByConcurrent(classifier, ConcurrentSkipListMap::new,
                                                   toCollection(HashSet::new)),
                              new GroupingByAssertion<>(classifier, ConcurrentSkipListMap.class,
                                                        new ToCollectionAssertion<Integer>(HashSet.class, false)));
    }

    @Test(dataProvider = "StreamTestData<Integer>", dataProviderClass = StreamTestDataProvider.class)
    public void testGroupingByWithMapping(String name, TestData.OfRef<Integer> data) throws Exception {
        Function<Integer, Integer> classifier = i -> i % 3;
        Function<Integer, Integer> mapper = i -> i * 2;

        exerciseMapCollection(data,
                              groupingBy(classifier, mapping(mapper, toList())),
                              new GroupingByAssertion<>(classifier, HashMap.class,
                                                        new MappingAssertion<>(mapper,
                                                                               new ToListAssertion<>())));
    }

    @Test
    public void testFlatMappingClose() {
        Function<Integer, Integer> classifier = i -> i;
        AtomicInteger ai = new AtomicInteger();
        Function<Integer, Stream<Integer>> flatMapper = i -> StreamSupport.of(i, i).onClose(ai::getAndIncrement);
        Map<Integer, List<Integer>> m = StreamSupport.of(1, 2).collect(groupingBy(classifier, flatMapping(flatMapper, toList())));
        assertEquals(m.size(), ai.get());
    }

    @Test(dataProvider = "StreamTestData<Integer>", dataProviderClass = StreamTestDataProvider.class)
    public void testGroupingByWithFlatMapping(String name, TestData.OfRef<Integer> data) throws Exception {
        Function<Integer, Integer> classifier = i -> i % 3;
        Function<Integer, Stream<Integer>> flatMapperByNull = i -> null;
        Function<Integer, Stream<Integer>> flatMapperBy0 = i -> StreamSupport.empty();
        Function<Integer, Stream<Integer>> flatMapperBy2 = i -> StreamSupport.of(i, i);

        exerciseMapCollection(data,
                              groupingBy(classifier, flatMapping(flatMapperByNull, toList())),
                              new GroupingByAssertion<>(classifier, HashMap.class,
                                                        new FlatMappingAssertion<>(flatMapperBy0,
                                                                                   new ToListAssertion<>())));
        exerciseMapCollection(data,
                              groupingBy(classifier, flatMapping(flatMapperBy0, toList())),
                              new GroupingByAssertion<>(classifier, HashMap.class,
                                                        new FlatMappingAssertion<>(flatMapperBy0,
                                                                                   new ToListAssertion<>())));
        exerciseMapCollection(data,
                              groupingBy(classifier, flatMapping(flatMapperBy2, toList())),
                              new GroupingByAssertion<>(classifier, HashMap.class,
                                                        new FlatMappingAssertion<>(flatMapperBy2,
                                                                                   new ToListAssertion<>())));
    }

    @Test(dataProvider = "StreamTestData<Integer>", dataProviderClass = StreamTestDataProvider.class)
    public void testTwoLevelGroupingBy(String name, TestData.OfRef<Integer> data) throws Exception {
        Function<Integer, Integer> classifier = i -> i % 6;
        Function<Integer, Integer> classifier2 = i -> i % 23;

        // Two-level groupBy
        exerciseMapCollection(data,
                              groupingBy(classifier, groupingBy(classifier2)),
                              new GroupingByAssertion<>(classifier, HashMap.class,
                                                        new GroupingByAssertion<>(classifier2, HashMap.class,
                                                                                  new ToListAssertion<>())));
        // with concurrent as upstream
        exerciseMapCollection(data,
                              groupingByConcurrent(classifier, groupingBy(classifier2)),
                              new GroupingByAssertion<>(classifier, ConcurrentHashMap.class,
                                                        new GroupingByAssertion<>(classifier2, HashMap.class,
                                                                                  new ToListAssertion<>())));
        // with concurrent as downstream
        exerciseMapCollection(data,
                              groupingBy(classifier, groupingByConcurrent(classifier2)),
                              new GroupingByAssertion<>(classifier, HashMap.class,
                                                        new GroupingByAssertion<>(classifier2, ConcurrentHashMap.class,
                                                                                  new ToListAssertion<>())));
        // with concurrent as upstream and downstream
        exerciseMapCollection(data,
                              groupingByConcurrent(classifier, groupingByConcurrent(classifier2)),
                              new GroupingByAssertion<>(classifier, ConcurrentHashMap.class,
                                                        new GroupingByAssertion<>(classifier2, ConcurrentHashMap.class,
                                                                                  new ToListAssertion<>())));

        // With explicit constructors
        exerciseMapCollection(data,
                              groupingBy(classifier, TreeMap::new, groupingBy(classifier2, TreeMap::new, toCollection(HashSet::new))),
                              new GroupingByAssertion<>(classifier, TreeMap.class,
                                                        new GroupingByAssertion<>(classifier2, TreeMap.class,
                                                                                  new ToCollectionAssertion<Integer>(HashSet.class, false))));
        // with concurrent as upstream
        exerciseMapCollection(data,
                              groupingByConcurrent(classifier, ConcurrentSkipListMap::new, groupingBy(classifier2, TreeMap::new, toList())),
                              new GroupingByAssertion<>(classifier, ConcurrentSkipListMap.class,
                                                        new GroupingByAssertion<>(classifier2, TreeMap.class,
                                                                                  new ToListAssertion<>())));
        // with concurrent as downstream
        exerciseMapCollection(data,
                              groupingBy(classifier, TreeMap::new, groupingByConcurrent(classifier2, ConcurrentSkipListMap::new, toList())),
                              new GroupingByAssertion<>(classifier, TreeMap.class,
                                                        new GroupingByAssertion<>(classifier2, ConcurrentSkipListMap.class,
                                                                                  new ToListAssertion<>())));
        // with concurrent as upstream and downstream
        exerciseMapCollection(data,
                              groupingByConcurrent(classifier, ConcurrentSkipListMap::new, groupingByConcurrent(classifier2, ConcurrentSkipListMap::new, toList())),
                              new GroupingByAssertion<>(classifier, ConcurrentSkipListMap.class,
                                                        new GroupingByAssertion<>(classifier2, ConcurrentSkipListMap.class,
                                                                                  new ToListAssertion<>())));
    }

    @Test(dataProvider = "StreamTestData<Integer>", dataProviderClass = StreamTestDataProvider.class)
    public void testGroupubgByWithReducing(String name, TestData.OfRef<Integer> data) throws Exception {
        Function<Integer, Integer> classifier = i -> i % 3;

        // Single-level simple reduce
        exerciseMapCollection(data,
                              groupingBy(classifier, reducing(0, Integers::sum)),
                              new GroupingByAssertion<>(classifier, HashMap.class,
                                                        new ReducingAssertion<>(0, LambdaTestHelpers.identity(), Integers::sum)));
        // with concurrent
        exerciseMapCollection(data,
                              groupingByConcurrent(classifier, reducing(0, Integers::sum)),
                              new GroupingByAssertion<>(classifier, ConcurrentHashMap.class,
                                                        new ReducingAssertion<>(0, LambdaTestHelpers.identity(), Integers::sum)));

        // With explicit constructors
        exerciseMapCollection(data,
                              groupingBy(classifier, TreeMap::new, reducing(0, Integers::sum)),
                              new GroupingByAssertion<>(classifier, TreeMap.class,
                                                        new ReducingAssertion<>(0, LambdaTestHelpers.identity(), Integers::sum)));
        // with concurrent
        exerciseMapCollection(data,
                              groupingByConcurrent(classifier, ConcurrentSkipListMap::new, reducing(0, Integers::sum)),
                              new GroupingByAssertion<>(classifier, ConcurrentSkipListMap.class,
                                                        new ReducingAssertion<>(0, LambdaTestHelpers.identity(), Integers::sum)));

        // Single-level map-reduce
        exerciseMapCollection(data,
                              groupingBy(classifier, reducing(0, mDoubler, Integers::sum)),
                              new GroupingByAssertion<>(classifier, HashMap.class,
                                                        new ReducingAssertion<>(0, mDoubler, Integers::sum)));
        // with concurrent
        exerciseMapCollection(data,
                              groupingByConcurrent(classifier, reducing(0, mDoubler, Integers::sum)),
                              new GroupingByAssertion<>(classifier, ConcurrentHashMap.class,
                                                        new ReducingAssertion<>(0, mDoubler, Integers::sum)));

        // With explicit constructors
        exerciseMapCollection(data,
                              groupingBy(classifier, TreeMap::new, reducing(0, mDoubler, Integers::sum)),
                              new GroupingByAssertion<>(classifier, TreeMap.class,
                                                        new ReducingAssertion<>(0, mDoubler, Integers::sum)));
        // with concurrent
        exerciseMapCollection(data,
                              groupingByConcurrent(classifier, ConcurrentSkipListMap::new, reducing(0, mDoubler, Integers::sum)),
                              new GroupingByAssertion<>(classifier, ConcurrentSkipListMap.class,
                                                        new ReducingAssertion<>(0, mDoubler, Integers::sum)));
    }

    @Test(dataProvider = "StreamTestData<Integer>", dataProviderClass = StreamTestDataProvider.class)
    public void testSimplePartitioningBy(String name, TestData.OfRef<Integer> data) throws Exception {
        Predicate<Integer> classifier = i -> i % 3 == 0;

        // Single-level partition to downstream List
        exerciseMapCollection(data,
                              partitioningBy(classifier),
                              new PartitioningByAssertion<>(classifier, new ToListAssertion<>()));
        exerciseMapCollection(data,
                              partitioningBy(classifier, toList()),
                              new PartitioningByAssertion<>(classifier, new ToListAssertion<>()));
    }

    @Test(dataProvider = "StreamTestData<Integer>", dataProviderClass = StreamTestDataProvider.class)
    public void testTwoLevelPartitioningBy(String name, TestData.OfRef<Integer> data) throws Exception {
        Predicate<Integer> classifier = i -> i % 3 == 0;
        Predicate<Integer> classifier2 = i -> i % 7 == 0;

        // Two level partition
        exerciseMapCollection(data,
                              partitioningBy(classifier, partitioningBy(classifier2)),
                              new PartitioningByAssertion<>(classifier,
                                                            new PartitioningByAssertion(classifier2, new ToListAssertion<>())));

        // Two level partition with reduce
        exerciseMapCollection(data,
                              partitioningBy(classifier, reducing(0, Integers::sum)),
                              new PartitioningByAssertion<>(classifier,
                                                            new ReducingAssertion<>(0, LambdaTestHelpers.identity(), Integers::sum)));
    }

    @Test(dataProvider = "StreamTestData<Integer>", dataProviderClass = StreamTestDataProvider.class)
    public void testComposeFinisher(String name, TestData.OfRef<Integer> data) throws Exception {
        List<Integer> asList = exerciseTerminalOps(data, s -> s.collect(toList()));
        List<Integer> asImmutableList = exerciseTerminalOps(data, s -> s.collect(collectingAndThen(toList(), Collections::unmodifiableList)));
        assertEquals(asList, asImmutableList);
        try {
            asImmutableList.add(0);
            fail("Expecting immutable result");
        }
        catch (UnsupportedOperationException ignored) { }
    }
}