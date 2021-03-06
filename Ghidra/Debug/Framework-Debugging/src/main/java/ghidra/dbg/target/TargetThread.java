/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.dbg.target;

import ghidra.dbg.DebuggerTargetObjectIface;

/**
 * A marker interface which indicates a thread, usually within a process
 * 
 * This object must be associated with a suitable {@link TargetExecutionStateful}. In most cases,
 * the object should just implement it.
 */
@DebuggerTargetObjectIface("Thread")
public interface TargetThread<T extends TargetThread<T>> extends TypedTargetObject<T> {
	enum Private {
		;
		private abstract class Cls implements TargetThread<Cls> {
		}
	}

	String TID_ATTRIBUTE_NAME = PREFIX_INVISIBLE + "tid";

	@SuppressWarnings({ "unchecked", "rawtypes" })
	Class<Private.Cls> tclass = (Class) TargetThread.class;
}
