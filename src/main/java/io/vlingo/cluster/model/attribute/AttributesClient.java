// Copyright © 2012-2018 Vaughn Vernon. All rights reserved.
//
// This Source Code Form is subject to the terms of the
// Mozilla Public License, v. 2.0. If a copy of the MPL
// was not distributed with this file, You can obtain
// one at https://mozilla.org/MPL/2.0/.

package io.vlingo.cluster.model.attribute;

public final class AttributesClient implements AttributesProtocol {
  private static AttributesClient client;
  
  protected final AttributesAgent agent;
  protected final AttributeSetRepository repository;
  
  public static AttributesClient instance() {
    return client;
  }
  
  protected static void stop() {
    client = null;
  }

  protected static synchronized AttributesClient with(final AttributesAgent agent, final AttributeSetRepository repository) {
    if (client == null) {
      client = new AttributesClient(agent, repository);
    }
    return client;
  }

  @Override
  public <T> void add(final String attributeSetName, final String attributeName, final T value) {
    agent.add(attributeSetName, attributeName, value);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> Attribute<T> attribute(final String attributeSetName, final String attributeName) {
    final AttributeSet set = repository.attributeSetOf(attributeSetName);
    if (set.isDefined()) {
      final TrackedAttribute tracked = set.attributeNamed(attributeName);
      if (tracked.isPresent()) {
        return (Attribute<T>) tracked.attribute;
      }
    }
    return (Attribute<T>) Attribute.Undefined;
  }

  @Override
  public <T> void replace(final String attributeSetName, final String attributeName, final T value) {
    agent.replace(attributeSetName, attributeName, value);
  }

  @Override
  public <T> void remove(final String attributeSetName, final String attributeName) {
    agent.remove(attributeSetName, attributeName);
  }

  @Override
  public String toString() {
    return "AttributesClient[agent=" + agent + " repository=" + repository + "]";
  }

  private AttributesClient(final AttributesAgent agent, final AttributeSetRepository repository) {
    this.agent = agent;
    this.repository = repository;
  }
}
