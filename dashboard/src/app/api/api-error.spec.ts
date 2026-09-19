import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import { apiErrorCodeOf, apiErrorOf, readableServerText } from './api-error';

function failure(body: unknown, status = 400): HttpErrorResponse {
  return new HttpErrorResponse({ error: body, status, statusText: 'Bad Request', url: '/admin/lessons/l-1' });
}

describe('readableServerText', () => {
  it('keeps a sentence that carries no JSON', () => {
    expect(readableServerText('Name at least one class to publish into.')).toBe(
      'Name at least one class to publish into.',
    );
  });

  /** `LessonSteps.Messages.of` appends the failing exception in parentheses. */
  it('takes the appended JSON out of a step message and leaves the advice', () => {
    const raw =
      'The AI returned an invalid answer twice. Retry, or edit Level 1 by hand and press Generate the other levels. ' +
      '({"stops":[{"id":"st-3","type":"choice"}]})';
    expect(readableServerText(raw)).toBe(
      'The AI returned an invalid answer twice. Retry, or edit Level 1 by hand and press Generate the other levels.',
    );
  });

  it('survives the truncation that cuts a blob mid-brace', () => {
    expect(readableServerText('The network dropped while talking to the AI service. ({"stops":[{"id":"st-3"…')).toBe(
      'The network dropped while talking to the AI service.',
    );
  });

  it('drops the validator’s JSON pointers and bare keys', () => {
    expect(readableServerText("Couldn't save, please rephrase. #/options: minItems; \"correctOptionId\": required")).toBe(
      "Couldn't save, please rephrase. minItems; required",
    );
  });

  it('says nothing at all when the message was only a document', () => {
    expect(readableServerText('{"code":"model_failed","detail":[1,2,3]}')).toBe('');
    expect(readableServerText('( … )')).toBe('');
  });
});

describe('apiErrorOf', () => {
  it('answers null for a message that reduces to nothing, so the caller uses its own sentence', () => {
    expect(apiErrorOf(failure({ code: 'model_failed', message: '[{"id":"st-1"}]' }))).toBeNull();
    // The code is still there for branching, which is the only thing it is for.
    expect(apiErrorCodeOf(failure({ code: 'model_failed', message: '[{"id":"st-1"}]' }))).toBe('model_failed');
  });

  it('hands back the prose, JSON-free, with the code beside it', () => {
    expect(apiErrorOf(failure({ code: 'conflict', message: 'That day already has a lesson. {"date":"2026-01-02"}' }))).toEqual(
      { code: 'conflict', message: 'That day already has a lesson.' },
    );
  });

  it('is null when the body is not the server speaking', () => {
    expect(apiErrorOf(failure('<html>502 Bad Gateway</html>', 502))).toBeNull();
    expect(apiErrorOf(new Error('offline'))).toBeNull();
  });
});
