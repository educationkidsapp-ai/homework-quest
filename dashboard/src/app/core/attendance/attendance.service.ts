import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AttendanceApi } from '../../api';
import { ClassAttendanceResponse, SaveAttendanceRequest } from './attendance.models';

@Injectable({ providedIn: 'root' })
export class AttendanceService {
  private readonly api = inject(AttendanceApi);

  getClassAttendance(classId: string, date?: string): Observable<ClassAttendanceResponse> {
    return this.api.getClassAttendance(classId, date);
  }

  saveClassAttendance(
    classId: string,
    request: SaveAttendanceRequest,
  ): Observable<ClassAttendanceResponse> {
    return this.api.saveClassAttendance(classId, request);
  }
}
