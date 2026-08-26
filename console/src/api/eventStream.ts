import { DEMO_MODE } from '../config'
import { openFakeEventStream } from '../mocks/fakeEventSource'
import { openRealEventStream } from './realEventSource'
import type { OpenEventStream } from './eventStreamTypes'

export const openRunEventStream: OpenEventStream = DEMO_MODE ? openFakeEventStream : openRealEventStream

export type { EventStreamHandle, EventStreamHandlers, OpenEventStream } from './eventStreamTypes'
