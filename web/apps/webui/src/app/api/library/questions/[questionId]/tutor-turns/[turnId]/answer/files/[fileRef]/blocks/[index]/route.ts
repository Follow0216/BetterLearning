import { isValidAnswerFileRef, parseAnswerBlockIndex } from "@/lib/api/answer-contract";
import { errorNoStore, webError } from "@/lib/api/auth-response";
import { libraryFailureResponse } from "@/lib/api/library-bff";
import { isValidQuestionId } from "@/lib/api/library-contract";
import { getTutorTurnAnswerBlock } from "@/lib/api/library-server";
import { isValidTutorTurnId } from "@/lib/api/tutor-turn-contract";

interface TutorTurnAnswerFileRouteContext {
  params: Promise<{ questionId: string; turnId: string; fileRef: string; index: string }>;
}

export async function GET(
  _request: Request,
  context: TutorTurnAnswerFileRouteContext,
): Promise<Response> {
  const { questionId, turnId, fileRef, index: rawIndex } = await context.params;
  if (!isValidQuestionId(questionId)) {
    return errorNoStore(400, webError("A0400", "题目编号不合法"));
  }
  if (!isValidTutorTurnId(turnId)) {
    return errorNoStore(400, webError("A0400", "辅导轮次编号不合法"));
  }
  if (!isValidAnswerFileRef(fileRef)) {
    return errorNoStore(400, webError("A0400", "作答文件编号不合法"));
  }
  const index = parseAnswerBlockIndex(rawIndex);
  if (index === null) {
    return errorNoStore(400, webError("A0400", "作答图块编号不合法"));
  }

  const result = await getTutorTurnAnswerBlock(questionId, turnId, fileRef, index);
  if (!result.ok) {
    return libraryFailureResponse(result.failure);
  }

  return new Response(result.data.body, {
    status: 200,
    headers: {
      "Content-Type": result.data.contentType,
      "Cache-Control": "private, no-store",
      "Content-Length": String(result.data.body.byteLength),
      "X-Content-Type-Options": "nosniff",
    },
  });
}
