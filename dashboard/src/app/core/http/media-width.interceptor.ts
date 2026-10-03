import { HttpContextToken, HttpInterceptorFn } from '@angular/common/http';

/**
 * **A downscaled picture** (D4): `GET /media/attachments/{id}?w=<px>` answers a JPEG no wider than
 * `w`, which is what a chat bubble wants instead of a 5 MB photograph.
 *
 * B5 serves `w` but keeps it out of the OpenAPI document — a new positional parameter on the
 * generated `attachment()` would have shifted every existing call — so the generated client cannot
 * name it. The width rides on the request's context instead and this adds it to the query: the
 * call itself is still the generated one.
 */
export const MEDIA_WIDTH = new HttpContextToken<number | null>(() => null);

export const mediaWidthInterceptor: HttpInterceptorFn = (request, next) => {
  const width = request.context.get(MEDIA_WIDTH);
  return width === null ? next(request) : next(request.clone({ setParams: { w: String(width) } }));
};
