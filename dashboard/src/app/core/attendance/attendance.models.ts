export type AttendanceStatus = 'PRESENT' | 'ABSENT' | 'LATE' | 'EXCUSED' | 'NOT_MARKED';

export interface ClassAttendanceItem {
  readonly childId: string;
  readonly childName: string;
  readonly avatarColor?: string;
  readonly photoUrl?: string;
  status: AttendanceStatus;
  notes?: string | null;
  readonly updatedAt?: string | null;
}

export interface ClassAttendanceResponse {
  readonly classId: string;
  readonly className: string;
  readonly date: string;
  readonly students: ClassAttendanceItem[];
  readonly totalCount: number;
  readonly presentCount: number;
  readonly absentCount: number;
  readonly lateCount: number;
  readonly excusedCount: number;
  readonly attendanceRate: number;
}

export interface SaveAttendanceItem {
  readonly childId: string;
  readonly status: AttendanceStatus;
  readonly notes?: string | null;
}

export interface SaveAttendanceRequest {
  readonly date: string;
  readonly items: SaveAttendanceItem[];
}
