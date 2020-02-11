/*
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.jicofo.bridge;

public class StressLevels
{
    public static final float LOW = 0, LOW_MEDIUM = .2f, MEDIUM = .5f, MEDIUM_HIGH = .7f, HIGH = 1;

    /**
     * Helper field to reduce allocations.
     */
    public static final float[] ALL_LEVELS = new float[] {
        StressLevels.LOW,
        StressLevels.LOW_MEDIUM,
        StressLevels.MEDIUM,
        StressLevels.MEDIUM_HIGH,
        StressLevels.HIGH
    };
}
