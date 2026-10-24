import type { NextRequest } from "next/server";

import { guardMutation } from "@/lib/api/auth-guard";
import { errorNoStore, jsonNoStore, readSessionToken, webError } from "@/lib/api/auth-response";
import {
  hasUnknownFields,
  libraryFailureResponse,
  readJsonObject,
} from "@/lib/api/library-bff";
import { isValidCollectionId } from "@/lib/api/library-contract";
import { moveQuestion } from "@/lib/api/library-server";

const ALLOWED_FIELDS = ["collectionId"] as const;

interface MoveRouteContext {
  params: Promise<{ questionId: string }>;
}

export async function PATCH(request: NextRequest, context: MoveRouteContext): Promise<Response> {
  const blocked = guardMutation(request, { requireJson: true });
  if (blocked) {
    return errorNoStore(blocked.status, blocked.payload);
  }

  const token = readSessionToken(request);
  if (token === null) {
    return errorNoStore(401, webError("A0200", "登录状态已失效，请重新登录"));
  }

  const { questionId } = await context.params;
  const body = await readJsonObject(request);
  if (!body.ok) {
    return body.response;
  }
  if (hasUnknownFields(body.value, ALLOWED_FIELDS)) {
    return errorNoStore(400, webError("A0427", "请求包含未知字段"));
  }

  const collectionId = body.value.collectionId;
  if (typeof collectionId !== "string" || !isValidCollectionId(collectionId)) {
    return errorNoStore(400, webError("A0400", "目标题集不合法"));
  }

  const result = await moveQuestion(token, questionId, collectionId);
  if (!result.ok) {
    return libraryFailureResponse(result.failure);
  }
  return jsonNoStore(result.data);
}