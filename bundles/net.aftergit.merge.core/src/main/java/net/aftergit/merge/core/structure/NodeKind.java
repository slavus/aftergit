package net.aftergit.merge.core.structure;

/** What a node of the structure tree is (structure-compare design §1). */
public enum NodeKind {
  IMPORTS, CLASS, INTERFACE, ENUM, RECORD, ANNOTATION, FIELD, CONSTANT, CONSTRUCTOR, METHOD, INITIALIZER, PROPERTY,
  HEADER, CLAUSE;

  /** A Java type: its members are its children. */
  public boolean isType() {
    return this == CLASS || this == INTERFACE || this == ENUM || this == RECORD || this == ANNOTATION;
  }
}
