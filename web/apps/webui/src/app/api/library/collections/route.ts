import type { NextRequest } from "next/server";

import { guardMutation } from "@/lib/api/auth-guard";
import { errorNoStore, jsonNoStore, readSessionToken, webError } from "@/lib/api/auth-response";
import {
  hasUnknownFields,
  libraryFailureResponse,
  readJsonObject,
} from "@/lib/api/library-bff";
import { normalizeCollectionName } from "@/lib/api/library-contract";
import { createCollection } from "@/lib/api/library-server";

const ALLOWED_FIELDS = ["name", "parentCollectionId"] as const;

export async function POST(request: NextRequest): Promise<Response> {
  const blocked = guardMutation(request, { requireJson: true });
  if (blocked) {
    return errorNoStore(blocked.status, blocked.payload);
  }

  const token = readSessionToken(request);
  if (token === null) {
    return errorNoStore(401, webError("A0200", "登录状态已失效，请重新登录"));
  }

  const body = await readJsonObject(request);
  if (!body.ok) {
    return body.response;
  }
  if (hasUnknownFields(body.value, ALLOWED_FIELDS)) {
    return errorNoStore(400, webError("A0427", "请求包含未知字段"));
  }

  const name = normalizeCollectionName(body.value.name);
  if (name === null) {
    return errorNoStore(400, webError("A0400", "题集名称需为 1–64 个字符"));
  }

  const rawParent = body.value.parentCollectionId;
  let parentCollectionId: string | null = null;
  if (rawParent !== null && rawParent !== undefined) {
    if (typeof rawParent !== "string" || rawParent.length === 0) {
      return errorNoStore(400, webError("A0400", "父题集不合法"));
    }
    parentCollectionId = rawParent;
  }

  const result = await createCollection(token, { name, parentCollectionId });
  if (!result.ok) {
    return libraryFailureResponse(result.failure);
  }
  return jsonNoStore(result.data, 201);
}