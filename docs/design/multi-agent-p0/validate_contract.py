#!/usr/bin/env python3
"""多 Agent P0 设计契约的离线结构校验；不触发服务、模型或网络。"""

from __future__ import annotations

import sys
from pathlib import Path

try:
    import yaml
except ImportError as exc:
    raise SystemExit("需要 PyYAML：请在隔离环境安装后重试") from exc


ROOT = Path(__file__).resolve().parent
SPEC = ROOT / "openapi.yaml"
DOC = ROOT / "contracts.md"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def all_refs(node: object) -> list[str]:
    refs: list[str] = []
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "$ref":
                refs.append(value)
            refs.extend(all_refs(value))
    elif isinstance(node, list):
        for value in node:
            refs.extend(all_refs(value))
    return refs


def resolve(spec: dict, ref: str) -> object:
    require(ref.startswith("#/"), f"外部引用未批准：{ref}")
    node: object = spec
    for part in ref[2:].split("/"):
        require(isinstance(node, dict) and part in node, f"悬空引用：{ref}")
        node = node[part]
    return node


def main() -> None:
    spec = yaml.safe_load(SPEC.read_text(encoding="utf-8"))
    doc = DOC.read_text(encoding="utf-8")
    require(isinstance(spec, dict) and spec.get("openapi") == "3.1.0", "OpenAPI 版本错误")
    require(spec.get("security") == [{"bearerAuth": []}], "默认 Bearer 认证缺失")
    refs = all_refs(spec)
    for ref in refs:
        resolve(spec, ref)

    paths = spec["paths"]
    operations = []
    for path, methods in paths.items():
        require(path.startswith("/api/v2/learning/"), f"路径前缀错误：{path}")
        for method, operation in methods.items():
            if method not in {"get", "post", "put", "patch", "delete"}:
                continue
            require("operationId" in operation, f"缺少 operationId：{method} {path}")
            require(operation.get("security", [{"bearerAuth": []}]) == [{"bearerAuth": []}],
                    f"缺少 Bearer 认证：{method} {path}")
            operations.append((method, path, operation["operationId"]))
    ids = [op[2] for op in operations]
    require(len(ids) == len(set(ids)), "operationId 重复")
    expected = {
        "listNotes", "createNote", "getNote", "updateNote", "deleteNote",
        "listSources", "getSource", "createTutorRequest", "listTutorRequests",
        "pendingTutorRequest", "getTutorRequest", "uploadTutorSnapshot",
        "cancelTutorRun", "retryTutorRun", "resolveCitation", "getCitationAsset",
    }
    require(expected == set(ids), f"操作不全或多余：{expected ^ set(ids)}")

    schemas = spec["components"]["schemas"]
    expected_schemas = {
        "ApiError", "Source", "SourceVersion", "Anchor", "Chunk", "Note",
        "TutorRequest", "Run", "Step", "Attempt", "Evidence", "TutorResult",
        "Citation", "OutboxEvent", "SaveManifest", "AnswerPoint",
        "CancelRequest", "RetryRequest", "HandwritingSegment", "WorldBounds",
    }
    require(expected_schemas <= set(schemas), "核心 DTO 不全")
    require(schemas["SourceType"]["enum"] ==
            ["QUESTION", "SAVED_ANSWER", "TUTOR_SNAPSHOT", "TUTOR_REPLY", "NOTE"],
            "来源类型与设计不一致")
    terminal = {"SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT"}
    require(terminal <= set(schemas["RunStatus"]["enum"]), "run 终态缺失")
    require("NEEDS_REVIEW" not in schemas["RunStatus"]["enum"], "旧可回退状态残留")
    require("currentRunId" in schemas["TutorRequest"]["properties"], "新 run 指针缺失")
    require("retryOfRunId" in schemas["Run"]["properties"], "run 重试关系缺失")
    snapshot_input = schemas["SnapshotInput"]["oneOf"]
    require(len(snapshot_input) == 2
            and {ref["$ref"] for ref in snapshot_input} ==
            {"#/components/schemas/NewCaptureInput", "#/components/schemas/ReuseRequestInput"},
            "快照创建/复用模式不全")
    require("sourceRequestId" in schemas["ReuseRequestInput"]["required"], "复用来源 ID 缺失")
    require("snapshotOriginRequestId" in schemas["TutorRequest"]["properties"],
            "原始冻结请求关联缺失")
    require("nextCursor" in schemas["TutorRequestPage"]["required"],
            "请求历史游标未强制返回")
    require(schemas["AnswerPoint"]["properties"]["pressure"]["maximum"] == 1,
            "压力字段约束缺失")
    require("null" in schemas["AnswerPoint"]["properties"]["pressure"]["type"],
            "可空压力字段缺失")
    require(set(schemas["AnswerStroke"]["properties"]["penKind"]["enum"]) ==
            {"ballpoint", "fountain", "highlighter", None}, "笔型枚举不兼容")
    require(schemas["SaveManifest"]["properties"]["schemaVersion"]["const"] == 1
            and schemas["AnswerDocument"]["properties"]["schemaVersion"]["const"] == 1,
            "M2 manifest schemaVersion 未锁定 1")
    segment_required = set(schemas["HandwritingSegment"]["required"])
    require({"blockIndex", "assetUrl", "blockWorldBounds", "pixelWidth",
             "pixelHeight", "regionWorldBounds"} <= segment_required,
            "跨块手写定位缺字段")
    require("segments" in schemas["HandwritingLocator"]["required"], "跨块定位数组缺失")
    require("quotedText" not in schemas["Citation"]["properties"],
            "未授权的引文不能随 Result.Citation 返回")
    resolution_required = set(schemas["CitationResolution"]["required"])
    require({"sourceCreatedAt", "sourceVersionCreatedAt", "currentVersion",
             "answerState"} <= resolution_required, "模态框来源元数据缺失")
    require("allOf" in schemas["CitationResolution"], "锚点类型未约束对应定位器")
    require("createdAt" in schemas["Source"]["required"], "来源创建时间缺失")
    require("expectedRunId" in schemas["CancelRequest"]["required"],
            "取消缺目标 run CAS")
    require("targetRunId" in schemas["RetryRequest"]["required"],
            "重试缺当前 run CAS")
    require(schemas["ApiError"]["required"] == ["code", "message", "traceId"],
            "既有错误对象被改变")

    parameters = spec["components"]["parameters"]
    require(parameters["Limit"]["schema"]["default"] == 20
            and parameters["Limit"]["schema"]["maximum"] == 100, "分页上限不一致")
    require("IfMatch" in parameters and "IdempotencyKey" in parameters, "ETag/幂等头缺失")
    for phrase in ("CALL_INTENT", "OUTCOME_UNKNOWN", "5 分钟", "2 分钟", "120 秒",
                   "10 分钟", "64 KiB", "7 天", "旧 run", "新快照",
                   "REUSE_REQUEST", "跨账号、跨题", "WAITING_INPUT/currentRunId=null",
                   "QUEUED/currentRunId", "CONTINUE", "当前 version 且 READY",
                   "UTF-16 code units", "已发布 result"):
        require(phrase in doc, f"正文缺少关键语义：{phrase}")

    # 有标准校验器时叠加规范校验；无此依赖也可复现本地结构门禁。
    try:
        from openapi_spec_validator import validate_spec
    except ImportError:
        standard = "未安装 openapi-spec-validator，跳过标准校验"
    else:
        validate_spec(spec)
        standard = "标准 OpenAPI 校验通过"
    print(f"PASS：{len(paths)} 路径，{len(operations)} 操作，{len(schemas)} DTO，"
          f"{len(refs)} 引用均可解析；{standard}")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, KeyError, TypeError, yaml.YAMLError) as exc:
        print(f"FAIL：{exc}", file=sys.stderr)
        raise SystemExit(1) from exc
