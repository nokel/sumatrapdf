/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

// json::Parse() hands each primitive value to a callback with the path as a
// StrNode list. The library/audiobook parsers all want the flattened path
// ("/characters[0]/name") and a class to keep their state in, which is what
// this gives them.

struct JsonVisitor {
    virtual ~JsonVisitor() = default;
    // return false to stop the parse
    virtual bool Visit(Str path, Str value, json::Type type) = 0;
};

inline void JsonVisitorOnValue(JsonVisitor* v, json::Value* val) {
    TempStr path = json::PathFormatTemp(val->path);
    if (!v->Visit(path, val->value, val->type)) {
        val->stop = true;
    }
}

inline bool JsonParseWithVisitor(Str data, JsonVisitor* v) {
    return json::Parse(data, MkFunc1(JsonVisitorOnValue, v));
}
