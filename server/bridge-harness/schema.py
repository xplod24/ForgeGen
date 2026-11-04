"""Small OpenAPI fixture validator for the schema constructs used by the supplied contract."""
import re
from datetime import datetime
from uuid import UUID


def validate(value, schema, spec):
    if "$ref" in schema:
        schema = spec["components"]["schemas"][schema["$ref"].split("/")[-1]]
    if "anyOf" in schema:
        for option in schema["anyOf"]:
            try:
                validate(value, option, spec)
                return
            except (AssertionError, ValueError, TypeError):
                pass
        raise AssertionError("No matching schema")
    t = schema.get("type")
    if t == "null":
        assert value is None
        return
    if t == "object":
        assert isinstance(value, dict)
        assert all(k in value for k in schema.get("required", []))
        props = schema.get("properties", {})
        if schema.get("additionalProperties") is False:
            assert not set(value) - set(props)
        for k, v in value.items():
            if k in props:
                validate(v, props[k], spec)
            elif isinstance(schema.get("additionalProperties"), dict):
                validate(v, schema["additionalProperties"], spec)
    elif t == "array":
        assert isinstance(value, list)
        assert len(value) <= schema.get("maxItems", len(value))
        for item in value:
            validate(item, schema["items"], spec)
    elif t == "string":
        assert isinstance(value, str)
        assert len(value) >= schema.get("minLength", 0)
        assert len(value) <= schema.get("maxLength", len(value))
        if schema.get("format") == "uuid":
            UUID(value)
        if schema.get("format") == "date-time":
            datetime.fromisoformat(value.replace("Z", "+00:00"))
        if "pattern" in schema:
            assert re.search(schema["pattern"], value)
    elif t == "integer":
        assert isinstance(value, int) and not isinstance(value, bool)
    elif t == "number":
        assert isinstance(value, (int, float)) and not isinstance(value, bool)
    elif t == "boolean":
        assert isinstance(value, bool)
    if "const" in schema:
        assert value == schema["const"]
    if "enum" in schema:
        assert value in schema["enum"]
    if t in ("integer", "number"):
        if "minimum" in schema:
            assert value >= schema["minimum"]
        if "maximum" in schema:
            assert value <= schema["maximum"]
        if "exclusiveMinimum" in schema:
            assert value > schema["exclusiveMinimum"]
