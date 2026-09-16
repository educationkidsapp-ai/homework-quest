/**
 * The child-app replica: what a lesson looks like on the phone, inside the device frame.
 *
 * `hq-phone-preview` is the only thing outside this folder needs — it owns the frame, the three
 * screens and the twenty-two per-type stop components. `hq-stop-preview` is exported for the
 * styleguide, which shows the stop types on their own, and `SEED_STOPS` for the specs and the
 * screenshot tests that need one real stop of every type.
 */
export { PhonePreviewComponent, type PreviewScreen } from './phone-preview.component';
export { StopPreviewComponent } from './stop-preview.component';
export { WorldMapPreviewComponent, type IslandState, type MapIsland } from './world-map.preview';
export { WrongAnswerPreviewComponent } from './wrong-answer.preview';
export { PipComponent, type PipPose, type PipSize } from './pip.component';
export { SEED_PLAY, SEED_STOPS, SEED_THEME } from './stop.fixtures';
export * from './stop.model';
