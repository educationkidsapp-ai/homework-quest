import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { BASE_PATH } from './generated/variables';
import { ClassAttendanceResponse, SaveAttendanceRequest } from '../core/attendance/attendance.models';

@Injectable({ providedIn: 'root' })
export class AttendanceApi {
  private readonly http = inject(HttpClient);
  private readonly basePath = inject(BASE_PATH, { optional: true }) ?? '';

  getClassAttendance(classId: string, date?: string): Observable<ClassAttendanceResponse> {
    const url = `${this.basePath}/teacher/classes/${encodeURIComponent(classId)}/attendance`;
    const params = date ? { date } : {};
    return this.http.get<ClassAttendanceResponse>(url, { params });
  }

  saveClassAttendance(
    classId: string,
    request: SaveAttendanceRequest,
  ): Observable<ClassAttendanceResponse> {
    const url = `${this.basePath}/teacher/classes/${encodeURIComponent(classId)}/attendance`;
    return this.http.post<ClassAttendanceResponse>(url, request);
  }
}
