import { HttpClient, HttpContext, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { MEDIA_WIDTH, mediaWidthInterceptor } from './media-width.interceptor';

/** D4: a thumbnail is `?w=` on the generated call's own request, and nothing else is touched. */
describe('mediaWidthInterceptor', () => {
  function setup() {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([mediaWidthInterceptor])), provideHttpClientTesting()],
    });
    return { http: TestBed.inject(HttpClient), backend: TestBed.inject(HttpTestingController) };
  }

  it('adds the width a request carries to its query', () => {
    const { http, backend } = setup();
    http.get('/media/attachments/att-1', { context: new HttpContext().set(MEDIA_WIDTH, 600) }).subscribe();

    expect(backend.expectOne('/media/attachments/att-1?w=600').request.params.get('w')).toBe('600');
  });

  it('leaves a request without one alone', () => {
    const { http, backend } = setup();
    http.get('/media/attachments/att-1').subscribe();

    expect(backend.expectOne('/media/attachments/att-1').request.params.keys()).toEqual([]);
  });
});
